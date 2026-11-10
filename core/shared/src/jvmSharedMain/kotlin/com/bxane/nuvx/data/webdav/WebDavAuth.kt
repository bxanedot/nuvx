package com.bxane.nuvx.data.webdav

object WebDavAuth {
    @Volatile var host: String? = null
    @Volatile var authHeader: String? = null

    fun update(host: String?, header: String?) {
        this.host = host
        authHeader = header
    }

    fun shouldAuthorize(requestHost: String): Boolean {
        val expected = host ?: return false
        val header = authHeader ?: return false
        if (header.isBlank()) return false
        return requestHost.equals(expected, ignoreCase = true)
    }
}
