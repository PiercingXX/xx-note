package com.piercingxx.xxnote.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * R8 / todo rule #7: one host means one host — one scheme, one name, one port.
 *
 * Every request leaving this client must target exactly the configured
 * origin — [scheme]://[host]:[port]. Anything else (a different host, the
 * same host on a different port, a cleartext downgrade of an HTTPS origin,
 * an IP literal for what happens to be the same machine) is a violation and
 * this interceptor THROWS: it does not log, it does not warn, it does not
 * fall back. A redirect cannot smuggle traffic to a third party either — the
 * client installs this interceptor and disables redirect following outright,
 * because a redirect is by definition an invitation to leave the one origin
 * (§15: TLS hard fail has no bypass; the same direction applies here).
 *
 * Installed as an application interceptor, so it sees every request the
 * caller issues before any socket exists — a refused request never opens a
 * connection, resolves DNS, or sends a byte.
 */
class OneHostInterceptor(
    private val host: String,
    private val port: Int,
    /** The configured origin's scheme; HTTPS in production (R8). */
    private val scheme: String = "https",
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (
            !url.host.equals(host, ignoreCase = true) ||
            url.port != port ||
            !url.scheme.equals(scheme, ignoreCase = true)
        ) {
            // redact(): strips userinfo/query if a caller ever embeds them;
            // we never log credentials, only where the request wanted to go.
            throw IllegalStateException(
                "one host means one host (R8): refusing ${request.method} " +
                    "${url.redact()} — configured origin is $scheme://$host:$port",
            )
        }
        return chain.proceed(request)
    }
}
