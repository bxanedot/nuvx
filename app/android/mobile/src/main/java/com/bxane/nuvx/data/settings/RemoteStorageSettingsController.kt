package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import com.bxane.nuvx.auth.AuthStore
import com.bxane.nuvx.data.webdav.update
import kotlinx.coroutines.flow.MutableStateFlow

internal class RemoteStorageSettingsController {
    private lateinit var prefs: SharedPreferences
    private lateinit var authStore: AuthStore

    val webdavUrl = MutableStateFlow("")
    val webdavUsername = MutableStateFlow("")
    val webdavPassword = MutableStateFlow("")
    val smbHost = MutableStateFlow("")
    val smbShare = MutableStateFlow("")
    val smbBasePath = MutableStateFlow("")
    val smbUsername = MutableStateFlow("")
    val smbPassword = MutableStateFlow("")

    fun initialize(prefs: SharedPreferences, authStore: AuthStore) {
        this.prefs = prefs
        this.authStore = authStore
    }

    fun read() {
        webdavUrl.value = prefs.getString(KEY_WEBDAV_URL, "").orEmpty()
        webdavUsername.value = prefs.getString(KEY_WEBDAV_USERNAME, "").orEmpty()
        webdavPassword.value = authStore.webdavPassword.orEmpty()
        smbHost.value = prefs.getString(KEY_SMB_HOST, "").orEmpty()
        smbShare.value = prefs.getString(KEY_SMB_SHARE, "").orEmpty()
        smbBasePath.value = prefs.getString(KEY_SMB_BASE_PATH, "").orEmpty()
        smbUsername.value = prefs.getString(KEY_SMB_USERNAME, "").orEmpty()
        smbPassword.value = authStore.smbPassword.orEmpty()
        publishSmbAuth()
        publishWebDavAuth()
    }

    fun setWebDavUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        webdavUrl.value = normalized
        prefs.edit().putString(KEY_WEBDAV_URL, normalized).apply()
        publishWebDavAuth()
    }

    fun setWebDavUsername(value: String) {
        val normalized = value.trim()
        webdavUsername.value = normalized
        prefs.edit().putString(KEY_WEBDAV_USERNAME, normalized).apply()
        publishWebDavAuth()
    }

    fun setWebDavPassword(value: String) {
        webdavPassword.value = value
        authStore.webdavPassword = value.ifEmpty { null }
        publishWebDavAuth()
    }

    fun clearWebDav() {
        setWebDavUrl("")
        setWebDavUsername("")
        setWebDavPassword("")
    }

    fun setSmbHost(value: String) {
        val normalized = value.trim()
        smbHost.value = normalized
        prefs.edit().putString(KEY_SMB_HOST, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbShare(value: String) {
        val normalized = value.trim().trim('/')
        smbShare.value = normalized
        prefs.edit().putString(KEY_SMB_SHARE, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbBasePath(value: String) {
        val normalized = value.trim().trim('/')
        smbBasePath.value = normalized
        prefs.edit().putString(KEY_SMB_BASE_PATH, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbUsername(value: String) {
        val normalized = value.trim()
        smbUsername.value = normalized
        prefs.edit().putString(KEY_SMB_USERNAME, normalized).apply()
        publishSmbAuth()
    }

    fun setSmbPassword(value: String) {
        smbPassword.value = value
        authStore.smbPassword = value.ifEmpty { null }
        publishSmbAuth()
    }

    fun clearSmb() {
        setSmbHost("")
        setSmbShare("")
        setSmbBasePath("")
        setSmbUsername("")
        setSmbPassword("")
    }

    private fun publishSmbAuth() {
        if (!this::prefs.isInitialized || !this::authStore.isInitialized) return
        com.bxane.nuvx.data.smb.SmbAuth.update(
            smbHost.value,
            smbShare.value,
            smbBasePath.value,
            smbUsername.value,
            smbPassword.value,
        )
    }

    private fun publishWebDavAuth() {
        if (!this::prefs.isInitialized || !this::authStore.isInitialized) return
        com.bxane.nuvx.data.webdav.WebDavAuth.update(
            webdavUrl.value,
            webdavUsername.value,
            webdavPassword.value,
        )
    }

    private companion object {
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USERNAME = "webdav_username"
        const val KEY_SMB_HOST = "smb_host"
        const val KEY_SMB_SHARE = "smb_share"
        const val KEY_SMB_BASE_PATH = "smb_base_path"
        const val KEY_SMB_USERNAME = "smb_username"
    }
}
