package com.aionemu.loginserver.utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.loginserver.configs.Config;
import com.alibaba.fastjson2.JSON;

/**
 * Validates session tokens issued by ShiguangCloud Control server.
 * <p>
 * Token Handoff flow:
 * <ol>
 *   <li>Player logs in via launcher → Control issues a one-time session token</li>
 *   <li>Launcher writes token to .sg-session → version.dll reads it</li>
 *   <li>Game client sends CM_LOGIN with password = "SG-{token}"</li>
 *   <li>This class validates the token via HTTP POST to Control</li>
 *   <li>Control consumes the token (single-use) and returns the account name</li>
 * </ol>
 * <p>
 * Design principles:
 * <ul>
 *   <li><b>Independent</b>: Separate from ExternalAuth; each can be enabled/disabled independently</li>
 *   <li><b>Decoupled</b>: Communicates with Control via HTTP; no shared state or DB dependency</li>
 *   <li><b>Reliable</b>: Connection timeout (3s), read timeout (5s), graceful error handling</li>
 *   <li><b>Observable</b>: Structured logging at every decision point (INFO for flow, WARN for failures)</li>
 *   <li><b>Extensible</b>: Response record can be extended; URL is configurable</li>
 * </ul>
 *
 * @author ShiguangCloud
 */
public class TokenHandoff {

	private static final Logger log = LoggerFactory.getLogger(TokenHandoff.class);

	/**
	 * Prefix that identifies a Token Handoff password in CM_LOGIN.
	 * When a password starts with this prefix, the remainder is treated
	 * as a session token to validate against the Control server.
	 */
	public static final String TOKEN_PREFIX = "SG-";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Whitelist for account names returned by Control's /api/token/validate.
	 * Matches 4-32 chars of [a-zA-Z0-9_-]. This is STRICTLY tighter than the
	 * account_data table constraint (which allows longer strings) to defend
	 * against SQL injection, path traversal, or log poisoning if Control is
	 * ever compromised or misconfigured. Tokens are already one-time, but
	 * this input is about to flow into AccountDAO lookups and log lines.
	 */
	private static final Pattern ACCOUNT_NAME_PATTERN =
			Pattern.compile("^[A-Za-z0-9_-]{4,32}$");

	private static final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.build();

	/**
	 * Checks whether a password value represents a Token Handoff request.
	 *
	 * @param password the password field from CM_LOGIN
	 * @return true if the password starts with the SG- prefix
	 */
	public static boolean isTokenHandoff(String password) {
		return password != null && password.startsWith(TOKEN_PREFIX) && password.length() > TOKEN_PREFIX.length();
	}

	/**
	 * Extracts the raw token from a Token Handoff password.
	 *
	 * @param password the password field starting with "SG-"
	 * @return the token portion without the prefix
	 */
	public static String extractToken(String password) {
		return password.substring(TOKEN_PREFIX.length());
	}

	/**
	 * Validates a session token against the ShiguangCloud Control server.
	 * <p>
	 * HTTP POST to Control's /api/token/validate endpoint.
	 * The token is consumed on the server side (single-use).
	 *
	 * @param token    the raw session token (without SG- prefix)
	 * @param clientIp the connecting client's IP address (for audit logging)
	 * @return validation result, or null if the Control server is unreachable
	 */
	public static Result validate(String token, String clientIp) {
		String url = Config.TOKEN_HANDOFF_URL;
		if (url == null || url.isBlank()) {
			log.warn("[TokenHandoff] TOKEN_HANDOFF_URL not configured; rejecting token from {}", clientIp);
			return null;
		}

		log.info("[TokenHandoff] Validating token for client {} (token={}...)", clientIp,
				token.length() > 8 ? token.substring(0, 8) : "***");

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.header("Content-Type", "application/json")
					.header("User-Agent", "BeyondLS-TokenHandoff/1.0")
					.timeout(REQUEST_TIMEOUT)
					.POST(HttpRequest.BodyPublishers.ofString(
							JSON.toJSONString(Map.of("token", token))))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 200) {
				Result result = JSON.parseObject(response.body(), Result.class);
				if (result == null || !result.ok() || result.account() == null || result.account().isEmpty()) {
					log.warn("[TokenHandoff] Control returned 200 but invalid body: {}", response.body());
					return null;
				}
				// Defense in depth: even though Control is trusted, refuse account
				// names that would be dangerous to pass downstream into DAO / logs.
				if (!ACCOUNT_NAME_PATTERN.matcher(result.account()).matches()) {
					log.error("[TokenHandoff] Control returned illegal account name (len={}); client={}; REJECTED",
							result.account().length(), clientIp);
					return null;
				}
				log.info("[TokenHandoff] Token validated successfully: account={}, server={}, client={}",
						result.account(), result.server(), clientIp);
				return result;
			}

			if (response.statusCode() == 401) {
				log.info("[TokenHandoff] Token rejected (expired/consumed/invalid) for client {}", clientIp);
				return new Result(false, "", "");
			}

			log.warn("[TokenHandoff] Control returned HTTP {} for client {}: {}",
					response.statusCode(), clientIp, response.body());
			return null;

		} catch (java.net.ConnectException e) {
			log.error("[TokenHandoff] Cannot connect to Control at {} — is shiguang-control running? (client={})",
					url, clientIp);
			return null;
		} catch (java.net.http.HttpTimeoutException e) {
			log.error("[TokenHandoff] Request to Control timed out after {}ms (client={})",
					REQUEST_TIMEOUT.toMillis(), clientIp);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			log.error("[TokenHandoff] Unexpected error validating token for client " + clientIp, e);
			return null;
		}
	}

	/**
	 * Result of a token validation request.
	 *
	 * @param ok      true if the token was valid and consumed
	 * @param account the account name associated with the token
	 * @param server  the server line ("5.8" or "4.8")
	 */
	public record Result(boolean ok, String account, String server) {}
}
