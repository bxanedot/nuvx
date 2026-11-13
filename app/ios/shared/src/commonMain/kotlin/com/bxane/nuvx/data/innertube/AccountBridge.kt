package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json




object AccountBridge {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    fun interface AccountCallback {
        fun onResult(json: String?, message: String?)
    }

    fun account(callback: AccountCallback) {
        val generation = Innertube.sessionGeneration
        bridgeScope.launch {
            try {
                Innertube.checkSession(generation)
                check(Innertube.cookie != null) { "Sign in to view your account" }
                Innertube.ensureSessionScope()
                val response = Innertube.accountMenu()
                Innertube.checkSession(generation)
                val account = InnertubeParser.parseAccount(response)
                if (account != null) {
                    callback.onResult(
                        json.encodeToString(Account.serializer(), account),
                        null,
                    )
                } else {
                    callback.onResult(null, "Not signed in")
                }
            } catch (e: Throwable) {
                callback.onResult(null, e.message ?: e.toString())
            }
        }
    }
}
