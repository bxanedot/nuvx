package com.bxane.nuvx.data.auth

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.settings.PlatformSettings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json



































object AccountStore {

    private val json = Json { ignoreUnknownKeys = true }

    private const val KEY = "account_sessions"



    @Serializable
    private data class StoredState(
        val accounts: List<GoogleAccountSession>,
        val activeAccountId: String? = null,
    )









    private var activeAccountId: String? = null
    private var activeProfileId: String? = null


    fun accounts(): List<GoogleAccountSession> = load()


    fun activeAccount(): GoogleAccountSession? {
        val all = accounts()
        if (all.isEmpty()) return null
        return all.firstOrNull { it.accountId == activeAccountId } ?: all.first()
    }


















    fun activeSelection(): ActiveSelection? {
        val account = activeAccount() ?: return null
        val profile = account.profiles.firstOrNull { it.profileId == activeProfileId }
            ?: account.profiles.firstOrNull()
            ?: return null
        return ActiveSelection(account, profile)
    }



    fun restore() {
        accounts()
        apply()
    }










    fun record(
        accountId: String,
        cookie: String,
        name: String = "",
        email: String = "",
        profiles: List<YouTubeProfile> = emptyList(),
    ): Boolean {
        if (cookie.isBlank()) return false
        val all = accounts().toMutableList()
        val id = accountId.ifBlank { sessionIdOf(cookie, null) }
        val session = GoogleAccountSession(
            accountId = id,
            cookie = cookie,
            name = name,
            email = email,
            profiles = profiles,
            activeProfileId = profiles.firstOrNull()?.profileId,
        )
        val at = all.indexOfFirst { it.accountId == id }
        if (at >= 0) all[at] = session else all.add(session)
        activeAccountId = id
        activeProfileId = session.activeProfileId
        if (!save(all)) {
            load()
            return false
        }
        apply()
        return true
    }


    fun forget(accountId: String): Boolean {
        val all = accounts().filterNot { it.accountId == accountId }
        if (activeAccountId == accountId) {
            activeAccountId = all.firstOrNull()?.accountId
            activeProfileId = all.firstOrNull()?.activeProfileId
        }
        if (!save(all)) {
            load()
            return false
        }
        apply()
        return true
    }









    fun select(accountId: String?, profileId: String?): Boolean {
        val all = accounts()
        val account = all.firstOrNull { it.accountId == accountId }
            ?: return false



        activeAccountId = account.accountId
        val profile = account.profiles.firstOrNull { it.profileId == profileId }
        activeProfileId = profile?.profileId
        if (!save(all.map {
            if (it.accountId == account.accountId) {
                it.copy(activeProfileId = activeProfileId)
            } else {
                it
            }
        })) {
            load()
            return false
        }
        apply()
        return true
    }






    fun step(forward: Boolean): Boolean {
        val all = accounts()
        val next = adjacentProfile(all, activeAccountId, activeProfileId, forward) ?: return false
        return select(next.first, next.second)
    }










    private fun apply() {
        val account = activeAccount()
        if (account == null) {
            Innertube.cookie = null
            Innertube.adoptPageScope(null, null, null)
            return
        }
        Innertube.cookie = account.cookie
        val profile = activeSelection()?.profile
        Innertube.adoptPageScope(profile?.pageId, profile?.dataSyncId, profile?.authUser)
        DebugLog.d(
            "acting as channel pageId=${profile?.pageId ?: "none"} " +
                "authUser=${profile?.authUser ?: "as-is"} (account=${account.accountId})",
        )
    }

    private fun load(): List<GoogleAccountSession> {
        val raw = PlatformSettings.getSecret(KEY) ?: return emptyList()
        val state = runCatching { json.decodeFromString(StoredState.serializer(), raw) }.getOrNull()
            ?: StoredState(
                accounts = runCatching {
                    json.decodeFromString(ListSerializer(GoogleAccountSession.serializer()), raw)
                }.getOrDefault(emptyList()),
            )
        val valid = state.accounts



            .filter { it.cookie.isNotBlank() }
        val active = valid.firstOrNull { it.accountId == state.activeAccountId } ?: valid.firstOrNull()
        activeAccountId = active?.accountId
        activeProfileId = active?.activeProfileId
        return valid
    }

    private fun save(all: List<GoogleAccountSession>): Boolean {
        if (all.isEmpty()) {
            PlatformSettings.putSecret(KEY, null)
            activeAccountId = null
            activeProfileId = null
            return PlatformSettings.getSecret(KEY) == null
        }
        val encoded = json.encodeToString(
            StoredState.serializer(), StoredState(all, activeAccountId)
        )
        PlatformSettings.putSecret(
            KEY,
            encoded,
        )
        val saved = PlatformSettings.getSecret(KEY) == encoded
        if (!saved) DebugLog.w("could not persist account sessions")
        return saved
    }
}
