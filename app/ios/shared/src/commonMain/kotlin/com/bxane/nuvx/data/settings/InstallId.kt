@file:OptIn(ExperimentalAtomicApi::class)

package com.bxane.nuvx.data.settings

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random


























object InstallId {

    private const val KEY = "install_id"








    private val cached = AtomicReference<String?>(null)







    fun get(): String {
        cached.load()?.let { return it }
        val minted = reuseOrMint(
            stored = PlatformSettings.getString(KEY, ""),
            mint = ::mint,
            persist = { PlatformSettings.putString(KEY, it) },
        )




        val settled = PlatformSettings.getString(KEY, "").ifEmpty { minted }
        cached.store(settled)
        return settled
    }












    internal fun reuseOrMint(stored: String?, mint: () -> String, persist: (String) -> Unit): String {
        val existing = stored?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val fresh = mint()
        persist(fresh)
        return fresh
    }













    internal fun mint(): String {
        val bytes = Random.nextBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            HEX[value ushr 4].toString() + HEX[value and 0x0F]
        }
        return buildString(36) {
            append(hex, 0, 8); append('-')
            append(hex, 8, 12); append('-')
            append(hex, 12, 16); append('-')
            append(hex, 16, 20); append('-')
            append(hex, 20, 32)
        }
    }

    private const val HEX = "0123456789abcdef"
}
