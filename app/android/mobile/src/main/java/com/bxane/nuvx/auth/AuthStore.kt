package com.bxane.nuvx.auth

import android.content.Context
import android.content.SharedPreferences

class AuthStore(context: Context) {

    private val prefs: SharedPreferences =
        EncryptedPrefs.open(context, "nuvx_auth", "nuvx_auth_plain")

    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    var sessions: List<GoogleAccountSession>
        get() {
            val saved = sessionsFromJson(prefs.getString(KEY_SESSIONS, null))
            if (saved.isNotEmpty()) return saved
            val legacy = cookie ?: return emptyList()
            val profile = YouTubeProfile(
                profileId = profileId(channelPageId, channelDataSyncId, channelName ?: "Personal"),
                name = channelName ?: "Personal",
                pageId = channelPageId,
                dataSyncId = channelDataSyncId,
                authUser = channelAuthUser,
                isBrandAccount = channelPageId != null,
            )
            return listOf(GoogleAccountSession(
                accountId = sessionId(legacy, channelDataSyncId), cookie = legacy,
                profiles = listOf(profile), activeProfileId = profile.profileId,
            )).also { replaceSessions(it) }
        }
        set(value) = replaceSessions(value)

    var activeAccountId: String?
        get() = prefs.getString(KEY_ACTIVE_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_ACCOUNT, value).apply()

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    val activeSession: GoogleAccountSession?
        get() = sessions.firstOrNull { it.accountId == activeAccountId }
            ?: sessions.firstOrNull()

    fun replaceSessions(value: List<GoogleAccountSession>) {
        prefs.edit().putString(KEY_SESSIONS, value.toJson()).apply()
    }

    fun upsertSession(session: GoogleAccountSession, activate: Boolean = true) {
        val existing = sessions
        val index = existing.indexOfFirst { it.accountId == session.accountId }
        val next = if (index >= 0) {
            existing.toMutableList().also { it[index] = session }
        } else {
            existing + session
        }
        replaceSessions(next)
        if (activate) select(session.accountId, session.activeProfileId)
    }

    fun select(accountId: String, profileId: String?) {
        activeAccountId = accountId
        activeProfileId = profileId
    }

    fun removeAccount(accountId: String): GoogleAccountSession? {
        val remaining = sessions.filterNot { it.accountId == accountId }
        replaceSessions(remaining)
        val fallback = remaining.firstOrNull()
        select(fallback?.accountId.orEmpty(), fallback?.activeProfileId)
        prefs.edit().putString(KEY_COOKIE, fallback?.cookie).apply()
        BrowserSession.clearGoogleCookies()
        return fallback
    }

    val isSignedIn: Boolean
        get() = activeSession?.cookie?.let { hasApiSid(it) } == true

    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    var webdavPassword: String?
        get() = prefs.getString(KEY_WEBDAV_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_WEBDAV_PASSWORD, value).apply()

    var smbPassword: String?
        get() = prefs.getString(KEY_SMB_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_SMB_PASSWORD, value).apply()

    val channelPageId: String? get() = prefs.getString(KEY_CHANNEL_PAGE_ID, null)
    val channelDataSyncId: String? get() = prefs.getString(KEY_CHANNEL_DATASYNC_ID, null)

    val channelName: String? get() = prefs.getString(KEY_CHANNEL_NAME, null)

    val channelAuthUser: String? get() = prefs.getString(KEY_CHANNEL_AUTH_USER, null)

    fun selectChannel(
        pageId: String?,
        dataSyncId: String?,
        name: String?,
        authUser: String? = null,
    ) = prefs.edit()
        .putString(KEY_CHANNEL_PAGE_ID, pageId)
        .putString(KEY_CHANNEL_DATASYNC_ID, dataSyncId)
        .putString(KEY_CHANNEL_NAME, name)
        .putString(KEY_CHANNEL_AUTH_USER, authUser)
        .apply()

    fun setChannelName(name: String?) =
        prefs.edit().putString(KEY_CHANNEL_NAME, name).apply()

    fun clearChannel() = prefs.edit()
        .remove(KEY_CHANNEL_PAGE_ID)
        .remove(KEY_CHANNEL_DATASYNC_ID)
        .remove(KEY_CHANNEL_NAME)
        .remove(KEY_CHANNEL_AUTH_USER)
        .apply()

    fun onNewSession(cookie: String) {
        this.cookie = cookie
        clearChannel()
    }

    fun signOut() {
        prefs.edit().remove(KEY_COOKIE).remove(KEY_SESSIONS)
            .remove(KEY_ACTIVE_ACCOUNT).remove(KEY_ACTIVE_PROFILE).apply()
        clearChannel()
        BrowserSession.clearGoogleCookies()
    }

    companion object {
        fun hasApiSid(cookieHeader: String): Boolean =
            cookieHeader.split(';').any { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name in API_SID_NAMES && value.isNotEmpty()
            }

        private val API_SID_NAMES =
            setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        private const val KEY_COOKIE = "cookie"
        private const val KEY_SESSIONS = "google_account_sessions_v2"
        private const val KEY_ACTIVE_ACCOUNT = "active_google_account_id_v2"
        private const val KEY_ACTIVE_PROFILE = "active_youtube_profile_id_v2"
        private const val KEY_CHANNEL_PAGE_ID = "channel_page_id"
        private const val KEY_CHANNEL_DATASYNC_ID = "channel_datasync_id"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_CHANNEL_AUTH_USER = "channel_auth_user"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_SMB_PASSWORD = "smb_password"
    }
}
