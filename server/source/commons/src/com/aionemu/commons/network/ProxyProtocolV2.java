package com.aionemu.commons.network;

import java.io.EOFException;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Parser for HAProxy PROXY Protocol v2 binary headers (spec section 2.2).
 *
 * Used by {@link Acceptor} when a {@link ServerCfg} declares expectProxyV2 =
 * true — the Acceptor does a BLOCKING read of the header bytes BEFORE the
 * socket is registered with the read/write Dispatcher. This ensures that the
 * Selector-based read loop sees only game protocol bytes, and that the
 * initial server-sent handshake packet (SM_KEY) is not emitted until the
 * real client IP has been recovered.
 *
 * Header layout (v4 example, 28 bytes total):
 *   [12 bytes signature] [0x21 ver+cmd] [0x11 fam+proto] [0x000C length BE]
 *   [4 bytes src IP] [4 bytes dst IP] [2 bytes src port BE] [2 bytes dst port BE]
 *
 * Behavior:
 *   - Signature mismatch → IOException (caller MUST close the socket)
 *   - Truncated/timed-out read → IOException
 *   - UNSPEC family → ProxyInfo with null ip (not an error; proceed)
 *   - IPv4 → ProxyInfo with dotted-quad ip + port
 *   - IPv6 → ProxyInfo with canonical v6 ip + port
 */
public final class ProxyProtocolV2 {

	/** 12-byte magic that starts every PROXY v2 header. */
	private static final byte[] SIGNATURE = new byte[]{
		(byte) 0x0D, (byte) 0x0A, (byte) 0x0D, (byte) 0x0A,
		(byte) 0x00, (byte) 0x0D, (byte) 0x0A, (byte) 0x51,
		(byte) 0x55, (byte) 0x49, (byte) 0x54, (byte) 0x0A,
	};

	private static final int VER_CMD_LOCAL = 0x20;    // v2 + LOCAL command (health check)
	private static final int VER_CMD_PROXY = 0x21;    // v2 + PROXY command
	private static final int FAM_UNSPEC    = 0x00;
	private static final int FAM_INET_TCP  = 0x11;
	private static final int FAM_INET6_TCP = 0x21;

	private static final int ADDR_LEN_IPV4 = 12;      // 4+4+2+2
	private static final int ADDR_LEN_IPV6 = 36;      // 16+16+2+2
	private static final int MAX_ADDR_LEN  = 216;     // spec minimum to accept

	private ProxyProtocolV2() {}

	/**
	 * Decoded header fields. Caller stores realIp on the AConnection.
	 */
	public static final class ProxyInfo {
		public final String realIp;   // may be null if UNSPEC
		public final int realPort;    // 0 if UNSPEC

		private ProxyInfo(String realIp, int realPort) {
			this.realIp = realIp;
			this.realPort = realPort;
		}
	}

	/**
	 * Synchronously read + parse a PROXY v2 header from sc.
	 *
	 * PRECONDITIONS:
	 *   - sc must be in blocking mode (the caller should configureBlocking(true)
	 *     first and restore to non-blocking after this returns successfully).
	 *   - sc.socket().setSoTimeout(timeoutMs) should be applied so truncated /
	 *     slow clients do not block the Accept dispatcher thread indefinitely.
	 *
	 * Returns ProxyInfo on success; throws IOException on signature mismatch,
	 * short read, or unsupported family.
	 */
	public static ProxyInfo parseBlocking(SocketChannel sc) throws IOException {
		// Stage 1: read the 16-byte fixed header (12 sig + 1 ver/cmd + 1 fam + 2 len).
		ByteBuffer fixed = ByteBuffer.allocate(16);
		readFully(sc, fixed);
		fixed.flip();

		// Verify signature
		for (int i = 0; i < SIGNATURE.length; i++) {
			if (fixed.get(i) != SIGNATURE[i]) {
				throw new IOException("PROXY v2 signature mismatch at byte " + i
					+ " (direct connection not allowed when expectProxyV2=true)");
			}
		}

		int verCmd = fixed.get(12) & 0xFF;
		if (verCmd != VER_CMD_PROXY && verCmd != VER_CMD_LOCAL) {
			throw new IOException("PROXY v2 unsupported ver/cmd 0x" + Integer.toHexString(verCmd));
		}
		int family = fixed.get(13) & 0xFF;
		int addrLen = ((fixed.get(14) & 0xFF) << 8) | (fixed.get(15) & 0xFF);

		if (addrLen > MAX_ADDR_LEN) {
			throw new IOException("PROXY v2 addr_len " + addrLen + " exceeds max " + MAX_ADDR_LEN);
		}

		// LOCAL command is used by upstream load-balancers for their own health
		// checks. Per spec, the receiver MUST use the real socket addresses and
		// ignore any address block that follows. We drain the addrLen bytes to
		// keep the stream aligned, but return a null ProxyInfo so the Acceptor
		// falls back to the raw peer address.
		if (verCmd == VER_CMD_LOCAL) {
			if (addrLen > 0) {
				ByteBuffer drain = ByteBuffer.allocate(addrLen);
				readFully(sc, drain);
			}
			return new ProxyInfo(null, 0);
		}

		if (addrLen == 0) {
			// UNSPEC: no addresses to read.
			return new ProxyInfo(null, 0);
		}

		// Stage 2: read the variable-length address block.
		ByteBuffer addr = ByteBuffer.allocate(addrLen);
		readFully(sc, addr);
		addr.flip();

		if (family == FAM_INET_TCP) {
			if (addrLen < ADDR_LEN_IPV4) {
				throw new IOException("PROXY v2 IPv4 addr block too short: " + addrLen);
			}
			byte[] srcIp = new byte[4];
			addr.get(srcIp);
			byte[] dstIp = new byte[4];
			addr.get(dstIp);
			int srcPort = ((addr.get() & 0xFF) << 8) | (addr.get() & 0xFF);
			// dst port intentionally ignored
			addr.get(); addr.get();
			String ipStr = (srcIp[0] & 0xFF) + "." + (srcIp[1] & 0xFF) + "."
				+ (srcIp[2] & 0xFF) + "." + (srcIp[3] & 0xFF);
			return new ProxyInfo(ipStr, srcPort);
		}

		if (family == FAM_INET6_TCP) {
			if (addrLen < ADDR_LEN_IPV6) {
				throw new IOException("PROXY v2 IPv6 addr block too short: " + addrLen);
			}
			byte[] srcIp = new byte[16];
			addr.get(srcIp);
			byte[] dstIp = new byte[16];
			addr.get(dstIp);
			int srcPort = ((addr.get() & 0xFF) << 8) | (addr.get() & 0xFF);
			addr.get(); addr.get(); // dst port
			InetAddress ia = Inet6Address.getByAddress(null, srcIp, -1);
			return new ProxyInfo(ia.getHostAddress(), srcPort);
		}

		if (family == FAM_UNSPEC) {
			return new ProxyInfo(null, 0);
		}

		throw new IOException("PROXY v2 unknown address family 0x" + Integer.toHexString(family));
	}

	private static void readFully(SocketChannel sc, ByteBuffer buf) throws IOException {
		while (buf.hasRemaining()) {
			int n = sc.read(buf);
			if (n < 0) {
				throw new EOFException("EOF during PROXY v2 header read (wanted "
					+ buf.remaining() + " more bytes)");
			}
			if (n == 0) {
				// In blocking mode, 0 returns should not happen — but guard anyway.
				throw new IOException("read returned 0 in blocking mode");
			}
		}
	}
}
