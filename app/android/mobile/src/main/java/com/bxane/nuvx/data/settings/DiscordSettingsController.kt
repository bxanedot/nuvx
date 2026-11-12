package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import com.bxane.nuvx.auth.AuthStore
import kotlinx.coroutines.flow.MutableStateFlow

internal class DiscordSettingsController {
    private lateinit var prefs: SharedPreferences
    private lateinit var authStore: AuthStore

    val discordToken = MutableStateFlow("")
    val discordUsername = MutableStateFlow("")
    val discordName = MutableStateFlow("")
    val discordAvatar = MutableStateFlow("")
    val discordRpcEnabled = MutableStateFlow(true)
    val discordUseDetails = MutableStateFlow(false)
    val discordShowAudioQuality = MutableStateFlow(true)
    val discordAdvancedMode = MutableStateFlow(false)
    val discordStatus = MutableStateFlow("online")
    val discordActivityType = MutableStateFlow("listening")
    val discordActivityName = MutableStateFlow("")
    val discordButton1Text = MutableStateFlow("")
    val discordButton1Visible = MutableStateFlow(true)
    val discordButton2Text = MutableStateFlow("")
    val discordButton2Visible = MutableStateFlow(true)
    val discordInfoDismissed = MutableStateFlow(false)

    fun initialize(prefs: SharedPreferences, authStore: AuthStore) {
        this.prefs = prefs
        this.authStore = authStore
    }

    fun read() {
        discordToken.value = authStore.discordToken.orEmpty()
        discordUsername.value = prefs.getString(KEY_DISCORD_USERNAME, "").orEmpty()
        discordName.value = prefs.getString(KEY_DISCORD_NAME, "").orEmpty()
        discordAvatar.value = prefs.getString(KEY_DISCORD_AVATAR, "").orEmpty()
        discordRpcEnabled.value = prefs.getBoolean(KEY_DISCORD_RPC_ENABLED, true)
        discordUseDetails.value = prefs.getBoolean(KEY_DISCORD_USE_DETAILS, false)
        discordShowAudioQuality.value = prefs.getBoolean(KEY_DISCORD_SHOW_AUDIO_QUALITY, true)
        discordAdvancedMode.value = prefs.getBoolean(KEY_DISCORD_ADVANCED_MODE, false)
        discordStatus.value = prefs.getString(KEY_DISCORD_STATUS, "online").orEmpty()
        discordActivityType.value = prefs.getString(KEY_DISCORD_ACTIVITY_TYPE, "listening").orEmpty()
        discordActivityName.value = prefs.getString(KEY_DISCORD_ACTIVITY_NAME, "").orEmpty()
        discordButton1Text.value = prefs.getString(KEY_DISCORD_BUTTON_1_TEXT, "").orEmpty()
        discordButton1Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, true)
        discordButton2Text.value = prefs.getString(KEY_DISCORD_BUTTON_2_TEXT, "").orEmpty()
        discordButton2Visible.value = prefs.getBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, true)
        discordInfoDismissed.value = prefs.getBoolean(KEY_DISCORD_INFO_DISMISSED, false)
    }

    fun setToken(value: String) {
        if (discordToken.value != value) setAccount("", "", null)
        discordToken.value = value
        authStore.discordToken = value.ifEmpty { null }
    }

    fun setAccount(username: String, name: String, avatar: String?) {
        discordUsername.value = username
        discordName.value = name
        discordAvatar.value = avatar.orEmpty()
        prefs.edit()
            .putString(KEY_DISCORD_USERNAME, username)
            .putString(KEY_DISCORD_NAME, name)
            .putString(KEY_DISCORD_AVATAR, avatar.orEmpty())
            .apply()
    }

    fun setRpcEnabled(value: Boolean) {
        discordRpcEnabled.value = value
        prefs.edit().putBoolean(KEY_DISCORD_RPC_ENABLED, value).apply()
    }

    fun setUseDetails(value: Boolean) {
        discordUseDetails.value = value
        prefs.edit().putBoolean(KEY_DISCORD_USE_DETAILS, value).apply()
    }

    fun setShowAudioQuality(value: Boolean) {
        discordShowAudioQuality.value = value
        prefs.edit().putBoolean(KEY_DISCORD_SHOW_AUDIO_QUALITY, value).apply()
    }

    fun setAdvancedMode(value: Boolean) {
        discordAdvancedMode.value = value
        prefs.edit().putBoolean(KEY_DISCORD_ADVANCED_MODE, value).apply()
    }

    fun setStatus(value: String) {
        discordStatus.value = value
        prefs.edit().putString(KEY_DISCORD_STATUS, value).apply()
    }

    fun setActivityType(value: String) {
        discordActivityType.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_TYPE, value).apply()
    }

    fun setActivityName(value: String) {
        discordActivityName.value = value
        prefs.edit().putString(KEY_DISCORD_ACTIVITY_NAME, value).apply()
    }

    fun setButton1Text(value: String) {
        discordButton1Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_1_TEXT, value).apply()
    }

    fun setButton1Visible(value: Boolean) {
        discordButton1Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_1_VISIBLE, value).apply()
    }

    fun setButton2Text(value: String) {
        discordButton2Text.value = value
        prefs.edit().putString(KEY_DISCORD_BUTTON_2_TEXT, value).apply()
    }

    fun setButton2Visible(value: Boolean) {
        discordButton2Visible.value = value
        prefs.edit().putBoolean(KEY_DISCORD_BUTTON_2_VISIBLE, value).apply()
    }

    fun setInfoDismissed(value: Boolean) {
        discordInfoDismissed.value = value
        prefs.edit().putBoolean(KEY_DISCORD_INFO_DISMISSED, value).apply()
    }

    private companion object {
        const val KEY_DISCORD_USERNAME = "discord_username"
        const val KEY_DISCORD_NAME = "discord_name"
        const val KEY_DISCORD_AVATAR = "discord_avatar"
        const val KEY_DISCORD_RPC_ENABLED = "discord_rpc_enabled"
        const val KEY_DISCORD_USE_DETAILS = "discord_use_details"
        const val KEY_DISCORD_SHOW_AUDIO_QUALITY = "discord_show_audio_quality"
        const val KEY_DISCORD_ADVANCED_MODE = "discord_advanced_mode"
        const val KEY_DISCORD_STATUS = "discord_status"
        const val KEY_DISCORD_ACTIVITY_TYPE = "discord_activity_type"
        const val KEY_DISCORD_ACTIVITY_NAME = "discord_activity_name"
        const val KEY_DISCORD_BUTTON_1_TEXT = "discord_button_1_text"
        const val KEY_DISCORD_BUTTON_1_VISIBLE = "discord_button_1_visible"
        const val KEY_DISCORD_BUTTON_2_TEXT = "discord_button_2_text"
        const val KEY_DISCORD_BUTTON_2_VISIBLE = "discord_button_2_visible"
        const val KEY_DISCORD_INFO_DISMISSED = "discord_info_dismissed"
    }
}
