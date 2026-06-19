package online.automint.app.util

import android.net.Uri

object UrlAllowlist {

    private val firstPartyHosts: Set<String> = setOf(
        "automint.online",
        "897dgo89roy8rgtery7t.lol",
        "challenges.cloudflare.com",
    )

    private val oauthHosts: Set<String> = setOf(
        "accounts.google.com",
        "oauth2.googleapis.com",
        "oauth.telegram.org",
        "appleid.apple.com",
    )

    fun isFirstParty(uri: Uri): Boolean = match(uri, firstPartyHosts)

    fun isOAuthProvider(uri: Uri): Boolean = match(uri, oauthHosts)

    private fun match(uri: Uri, set: Set<String>): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return set.any { host == it || host.endsWith(".$it") }
    }
}
