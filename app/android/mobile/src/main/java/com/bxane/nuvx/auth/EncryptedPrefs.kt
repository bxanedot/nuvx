package com.bxane.nuvx.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bxane.nuvx.data.DebugLog as Log

internal object EncryptedPrefs {

    private const val TAG = "nuvx"

    private const val KEYSET_ENTRY = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

    private val opened = HashMap<String, SharedPreferences>()

    @Synchronized
    fun open(context: Context, name: String, plainName: String): SharedPreferences =
        opened.getOrPut(name) { resolve(context.applicationContext, name, plainName) }

    private fun resolve(context: Context, name: String, plainName: String): SharedPreferences {
        val encrypted = try {
            create(context, name)
        } catch (e: Throwable) {
            val staleKeyset = isUnverifiable(e) &&
                context.getSharedPreferences(name, Context.MODE_PRIVATE).contains(KEYSET_ENTRY)
            if (!staleKeyset) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable for $name, falling back: ${describe(e)}")
                return context.getSharedPreferences(plainName, Context.MODE_PRIVATE)
            }
            Log.w(TAG, "$name's keyset no longer matches this device's Keystore (${describe(e)}); recreating it")
            context.deleteSharedPreferences(name)
            try {
                create(context, name)
            } catch (retry: Throwable) {
                Log.w(TAG, "$name could not be recreated, falling back: ${describe(retry)}")
                return context.getSharedPreferences(plainName, Context.MODE_PRIVATE)
            }
        }
        moveIn(context, plainName, encrypted, name)
        return encrypted
    }

    private fun create(context: Context, name: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            name,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun moveIn(context: Context, plainName: String, encrypted: SharedPreferences, name: String) {
        val entries = context.getSharedPreferences(plainName, Context.MODE_PRIVATE).all
        if (entries.isEmpty()) return
        val edit = encrypted.edit()
        for ((key, value) in entries) {
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        if (!edit.commit()) {
            Log.w(TAG, "could not write $plainName into $name; keeping the plain copy")
            return
        }
        val stored = encrypted.all
        val unmatched = entries.keys.filter { stored[it] != entries[it] }
        if (unmatched.isNotEmpty()) {
            Log.w(TAG, "${unmatched.size} of ${entries.size} entries from $plainName did not read back; keeping the plain copy")
            return
        }
        context.deleteSharedPreferences(plainName)
        Log.d(TAG, "moved ${entries.size} entries from $plainName into $name")
    }

    private fun isUnverifiable(e: Throwable): Boolean = causes(e).any {
        it is javax.crypto.AEADBadTagException ||
            it.javaClass.name.endsWith("InvalidProtocolBufferException") ||
            (it.javaClass.name == "android.security.KeyStoreException" &&
                it.message.orEmpty().contains("VERIFICATION_FAILED", ignoreCase = true))
    }

    private fun describe(e: Throwable): String =
        causes(e).joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }

    private fun causes(e: Throwable): List<Throwable> =
        generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
}
