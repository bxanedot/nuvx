package com.bxane.nuvx.data.listentogether

import android.content.SharedPreferences
import com.bxane.nuvx.NuvxApplication
import com.bxane.nuvx.data.model.artworkAt
import java.security.MessageDigest
import java.util.UUID

internal data class PartyIdentity(
    val userId: String,
    val deviceId: String,
    val name: String,
    val avatar: String?,
)

internal class PartyIdentityResolver(
    private val preferences: () -> SharedPreferences,
    private val defaultNickname: () -> String,
    private val deviceIdKey: String,
    private val avatarSizePx: Int,
) {
    fun resolve(nickname: String = defaultNickname()): PartyIdentity? {
        val store = NuvxApplication.authStore
        if (!store.isSignedIn) return null
        val account = store.activeSession ?: return null
        val profile = account.profiles.firstOrNull { it.profileId == account.activeProfileId }
            ?: account.profiles.firstOrNull()
        val name = nickname.trim().takeIf { it.isNotBlank() }
            ?: profile?.name?.takeIf { it.isNotBlank() }
            ?: account.name.takeIf { it.isNotBlank() }
            ?: account.email.substringBefore('@').takeIf { it.isNotBlank() }
            ?: return null
        return PartyIdentity(
            userId = sha256("${account.accountId}:${profile?.profileId.orEmpty()}").take(32),
            deviceId = deviceId(),
            name = name,
            avatar = profile?.avatar?.takeIf { it.startsWith("http") }?.artworkAt(avatarSizePx),
        )
    }

    private fun deviceId(): String {
        val preferences = preferences()
        preferences.getString(deviceIdKey, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            preferences.edit().putString(deviceIdKey, it).apply()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
