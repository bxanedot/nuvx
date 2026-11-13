package com.bxane.nuvx.data.webdav

import com.bxane.nuvx.data.remote.WebDavConfig
import com.bxane.nuvx.data.http.CredentialPolicy
import com.bxane.nuvx.data.settings.AppSettings



























object WebDavAuth {


    fun headerFor(requestUrl: String): String? {
        val configured = AppSettings.webDavUrl.value
        if (!WebDavConfig.isConfigured(configured)) return null
        if (!CredentialPolicy.sameOrigin(requestUrl, configured)) return null
        return WebDavConfig.basicAuthHeader(
            AppSettings.webDavUsername.value,
            AppSettings.webDavPassword.value,
        )
    }


    fun authorizes(requestUrl: String): Boolean = headerFor(requestUrl) != null


    fun isConfigured(): Boolean = WebDavRepository.isConfigured()
}
