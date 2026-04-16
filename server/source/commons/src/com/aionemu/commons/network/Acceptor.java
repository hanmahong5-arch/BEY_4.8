package com.aionemu.commons.network;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class represents an <code>Acceptor</code> that will accept sockets<br>
 * connections dispatched by Accept <code>Dispatcher</code>. <code>Acceptor</code> is attachment<br>
 * of <code>ServerSocketChannel</code> <code>SelectionKey</code> registered on Accept <code>Dispatcher</code> <code>Selector</code>.<br>
 * <code>Acceptor</code> will create new <code>AConnection</code> object using <code>ConnectionFactory.create(SocketChannel socket)</code><br>
 * representing accepted socket, register it into one of ReadWrite <code>Dispatcher</code><br>
 * <code>Selector</code> as ready for io read operations.<br>
 *
 * @author -Nemesiss-
 * @see com.aionemu.commons.network.Dispatcher
 * @see java.nio.channels.ServerSocketChannel
 * @see java.nio.channels.SelectionKey
 * @see java.nio.channels.SocketChannel
 * @see java.nio.channels.Selector
 * @see com.aionemu.commons.network.AConnection
 * @see com.aionemu.commons.network.ConnectionFactory
 * @see com.aionemu.commons.network.NioServer
 */
public class Acceptor {

	private static final Logger log = LoggerFactory.getLogger(Acceptor.class);

	/**
	 * Blocking PROXY v2 pre-read timeout in milliseconds. Override via JVM
	 * system property {@code aion.proxy_v2.read_timeout_ms} for operators
	 * who want to tune against high-latency gate deployments. Bounded to
	 * [1000, 30000] to prevent foot-guns (zero timeout = deadlock risk;
	 * arbitrarily large = DoS vector on the Accept dispatcher thread).
	 */
	private static final int PROXY_V2_READ_TIMEOUT_MS = resolveProxyV2Timeout();

	private static int resolveProxyV2Timeout() {
		String override = System.getProperty("aion.proxy_v2.read_timeout_ms");
		if (override == null) {
			return 5000;
		}
		try {
			int v = Integer.parseInt(override.trim());
			if (v < 1000) {
				return 1000;
			}
			if (v > 30000) {
				return 30000;
			}
			return v;
		} catch (NumberFormatException nfe) {
			return 5000;
		}
	}

	/**
	 * <code>ConnectionFactory</code> that will create new <code>AConnection</code>
	 *
	 * @see com.aionemu.commons.network.ConnectionFactory
	 * @see com.aionemu.commons.network.AConnection
	 */
	private final ConnectionFactory factory;

	/**
	 * <code>NioServer</code> that created this Acceptor.
	 *
	 * @see com.aionemu.commons.network.NioServer
	 */
	private final NioServer nioServer;

	/**
	 * If true, every accepted connection must begin with a HAProxy PROXY
	 * Protocol v2 header (see {@link ProxyProtocolV2}). Configured per
	 * {@link ServerCfg}, so different ports on the same NioServer can have
	 * independent settings.
	 */
	private final boolean expectProxyV2;

	/**
	 * Constructor that accept <code>ConnectionFactory</code> and <code>NioServer</code> as parameter<br>
	 *
	 * @param factory
	 *          <code>ConnectionFactory</code> that will be used to<br>
	 * @param nioServer
	 *          <code>NioServer</code> that created this Acceptor object<br>
	 * @param expectProxyV2
	 *          if true, do a blocking PROXY v2 pre-read on every accept
	 *          before handing the connection to the factory.
	 * @see com.aionemu.commons.network.ConnectionFactory
	 * @see com.aionemu.commons.network.NioServer
	 * @see com.aionemu.commons.network.AConnection
	 */
	Acceptor(ConnectionFactory factory, NioServer nioServer, boolean expectProxyV2) {
		this.factory = factory;
		this.nioServer = nioServer;
		this.expectProxyV2 = expectProxyV2;
	}

	/**
	 * Method called by Accept <code>Dispatcher</code> <code>Selector</code> when socket<br>
	 * connects to <code>ServerSocketChannel</code> listening for connections.<br>
	 * New instance of <code>AConnection</code> will be created by <code>ConnectionFactory</code>,<br>
	 * socket representing accepted connection will be register into<br>
	 * one of ReadWrite <code>Dispatchers</code> <code>Selector as ready for io read operations.<br>
	 *
	 * When expectProxyV2 is true, a blocking read of the PROXY v2 header is
	 * performed BEFORE configureBlocking(false) so the downstream Dispatcher
	 * read loop (and the immediate SM_KEY server-first packet) does not race
	 * against the header bytes.
	 *
	 * @param key
	 *          <code>SelectionKey</code> representing <code>ServerSocketChannel</code> that is accepting<br>
	 *          new socket connection.
	 * @throws IOException
	 * @see com.aionemu.commons.network.Dispatcher
	 * @see java.nio.channels.ServerSocketChannel
	 * @see java.nio.channels.SelectionKey
	 * @see java.nio.channels.SocketChannel
	 * @see java.nio.channels.Selector
	 * @see com.aionemu.commons.network.AConnection
	 * @see com.aionemu.commons.network.ConnectionFactory
	 */
	public final void accept(SelectionKey key) throws IOException {
		// For an accept to be pending the channel must be a server socket channel
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
		// Accept the connection and make it non-blocking
		SocketChannel socketChannel = serverSocketChannel.accept();

		// PROXY Protocol v2 pre-read: must happen while still in blocking mode.
		// We enforce a read timeout so truncated/slow clients can't stall the
		// Accept dispatcher thread indefinitely.
		ProxyProtocolV2.ProxyInfo proxyInfo = null;
		if (expectProxyV2) {
			// Capture the raw peer early so rejection logs always carry an IP
			// even if the channel is torn down before parseBlocking completes.
			String peer = safePeer(socketChannel);
			try {
				socketChannel.configureBlocking(true);
				socketChannel.socket().setSoTimeout(PROXY_V2_READ_TIMEOUT_MS);
				proxyInfo = ProxyProtocolV2.parseBlocking(socketChannel);
			} catch (java.net.SocketTimeoutException ste) {
				log.warn("Rejecting connection: PROXY v2 pre-read timeout from peer={} after {}ms",
						peer, PROXY_V2_READ_TIMEOUT_MS);
				closeQuietly(socketChannel);
				return;
			} catch (IOException e) {
				log.warn("Rejecting connection: PROXY v2 pre-read failed peer={} reason={}",
						peer, e.getMessage());
				closeQuietly(socketChannel);
				return;
			}
		}

		socketChannel.configureBlocking(false);
		socketChannel.socket().setSoLinger(true, 10);
		socketChannel.socket().setTcpNoDelay(true);

		Dispatcher dispatcher = nioServer.getReadWriteDispatcher();
		AConnection<?> con = factory.create(socketChannel, dispatcher);

		if (con == null) {
			socketChannel.close();
			return;
		}

		// Propagate the real client IP (if any) onto the AConnection BEFORE
		// initialized() is called — this is critical because initialized()
		// typically sends SM_KEY and may log the client's IP.
		if (proxyInfo != null && proxyInfo.realIp != null) {
			con.setRealIp(proxyInfo.realIp, proxyInfo.realPort);
		}

		// register
		dispatcher.register(socketChannel, SelectionKey.OP_READ, con);
		// notify initialized :)
		con.initialized();
	}

	/**
	 * Returns the remote peer's host:port as a log-safe string, or "unknown"
	 * if the channel already closed or SecurityManager denies the lookup.
	 */
	private static String safePeer(SocketChannel sc) {
		try {
			java.net.SocketAddress sa = sc.getRemoteAddress();
			return sa == null ? "unknown" : sa.toString();
		} catch (IOException | RuntimeException ignored) {
			return "unknown";
		}
	}

	/**
	 * Close a SocketChannel swallowing IOException — only used on reject paths
	 * where the caller has already decided the connection is dead.
	 */
	private static void closeQuietly(SocketChannel sc) {
		try {
			sc.close();
		} catch (IOException ignored) {
		}
	}
}
