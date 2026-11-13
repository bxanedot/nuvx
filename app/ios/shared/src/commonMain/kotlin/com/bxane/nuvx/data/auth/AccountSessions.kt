package com.bxane.nuvx.data.auth

import com.bxane.nuvx.data.crypto.Sha256
import kotlinx.serialization.Serializable









@Serializable
data class GoogleAccountSession(
    val accountId: String,
    val cookie: String,
    val name: String = "",
    val email: String = "",
    val profiles: List<YouTubeProfile> = emptyList(),
    val activeProfileId: String? = null,
)


@Serializable
data class YouTubeProfile(
    val profileId: String,
    val name: String,
    val handle: String = "",
    val avatar: String? = null,
    val pageId: String? = null,
    val dataSyncId: String? = null,
    val authUser: String? = null,
    val isBrandAccount: Boolean = false,
)







@Serializable
data class ActiveSelection(
    val account: GoogleAccountSession,
    val profile: YouTubeProfile,
)











fun sessionIdOf(cookie: String, dataSyncId: String?): String =
    dataSyncId?.takeIf { it.isNotBlank() } ?: sha256Hex(cookie).take(24)














fun profileIdOf(pageId: String?, dataSyncId: String?, name: String): String =
    pageId?.takeIf { it.isNotBlank() }
        ?: dataSyncId?.takeIf { it.isNotBlank() }
        ?: "profile:${sha256Hex(name).take(16)}"






private fun sha256Hex(value: String): String =
    Sha256.digest(value.encodeToByteArray())
        .joinToString("") { byte ->
            val v = byte.toInt() and 0xff
            val digits = "0123456789abcdef"
            "${digits[v shr 4]}${digits[v and 0xf]}"
        }









fun flattenedProfiles(accounts: List<GoogleAccountSession>): List<Pair<String, String>> =
    accounts.flatMap { account -> account.profiles.map { account.accountId to it.profileId } }








fun adjacentProfile(
    accounts: List<GoogleAccountSession>,
    accountId: String?,
    profileId: String?,
    forward: Boolean,
): Pair<String, String>? {
    val items = flattenedProfiles(accounts)
    val index = items.indexOf(accountId to profileId)
    if (index < 0) return null
    val step = if (forward) 1 else -1
    return items.getOrNull(index + step)
}
