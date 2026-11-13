package com.bxane.nuvx.data.http

import io.ktor.http.Url


object CredentialPolicy {
    fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = Url(first); val b = Url(second)
        a.protocol == b.protocol && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }.getOrDefault(false)

    fun mayRedirect(from: String, to: String, carriesCredentials: Boolean): Boolean = runCatching {
        val a = Url(from); val b = Url(to)
        b.protocol.name in setOf("http", "https") && b.user.isNullOrEmpty() && b.password.isNullOrEmpty() &&
            !(a.protocol.name == "https" && b.protocol.name != "https") &&
            (!carriesCredentials || sameOrigin(from, to))
    }.getOrDefault(false)











    fun jarServes(host: String?): Boolean {
        val name = host?.lowercase()?.trimEnd('.') ?: return true
        return GOOGLE_DOMAINS.none { name == it || name.endsWith(".$it") }
    }

    private val GOOGLE_DOMAINS = listOf(
        "youtube.com", "youtube-nocookie.com", "youtu.be", "google.com", "googlevideo.com",
        "googleapis.com", "googleusercontent.com", "ytimg.com", "ggpht.com", "gstatic.com",
    )
}
