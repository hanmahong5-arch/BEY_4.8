package com.aionemu.commons.network;

import java.net.InetSocketAddress;

/**
 * This class represents ServerCfg for configuring NioServer
 *
 * @author -Nemesiss-, Neon
 *
 * expectProxyV2 : when true, every accepted connection must begin with a
 *                 HAProxy PROXY Protocol v2 header (shiguang-gate prepends this).
 *                 Enable ONLY for external-facing ports that sit behind the gate.
 */
public record ServerCfg(InetSocketAddress address, String clientDescription,
                        ConnectionFactory connectionFactory, boolean expectProxyV2) {

	/**
	 * Backward-compatible constructor: defaults expectProxyV2 to false for
	 * direct-connect (non-gated) setups and existing call sites.
	 */
	public ServerCfg(InetSocketAddress address, String clientDescription, ConnectionFactory connectionFactory) {
		this(address, clientDescription, connectionFactory, false);
	}

	public boolean isAnyLocalAddress() {
		return address.getAddress().isAnyLocalAddress();
	}

	public String getIP() {
		return address.getAddress().getHostAddress();
	}

	public int getPort() {
		return address.getPort();
	}

	public String getAddressInfo() {
		return (isAnyLocalAddress() ? "all addresses on port " : getIP() + ":") + getPort();
	}
}
