package com.bxane.nuvx.data.settings

import android.content.SharedPreferences
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

object TvPinThrottle {
    private var prefs: SharedPreferences? = null
    private val memoryAttempts = ConcurrentHashMap<String, Int>()
    private val memoryLockUntil = ConcurrentHashMap<String, Long>()

    private const val KEY_ATTEMPTS_PREFIX = "tv_pin_attempts_"
    private const val KEY_LOCK_UNTIL_PREFIX = "tv_pin_lock_until_"
    private const val KEY_LOCK_SINCE_PREFIX = "tv_pin_lock_since_"
    private const val KEY_LOCK_DELAY_PREFIX = "tv_pin_lock_delay_"

    private val memoryLockSince = ConcurrentHashMap<String, Long>()
    private val memoryLockDelay = ConcurrentHashMap<String, Long>()

    private fun monotonicNow(): Long = SystemClock.elapsedRealtime()

    fun init(preferences: SharedPreferences) {
        prefs = preferences
    }

    fun calculateDelayMs(failedCount: Int): Long {
        return when {
            failedCount < 5 -> 0L
            failedCount == 5 -> 10_000L
            failedCount == 6 -> 30_000L
            failedCount == 7 -> 60_000L
            failedCount == 8 -> 120_000L
            else -> 180_000L
        }
    }

    fun formatDuration(seconds: Int): String {
        return when {
            seconds <= 1 -> "1 second"
            seconds < 60 -> "$seconds seconds"
            else -> {
                val mins = kotlin.math.ceil(seconds / 60.0).toInt()
                if (mins <= 1) "1 minute" else "$mins minutes"
            }
        }
    }

    fun getDelayRemainingMs(key: String, now: Long = monotonicNow()): Long {
        val until = getLockUntil(key)
        if (until == 0L) return 0L
        if (now < getLong(key, KEY_LOCK_SINCE_PREFIX, memoryLockSince)) {
            return getLong(key, KEY_LOCK_DELAY_PREFIX, memoryLockDelay)
        }
        val remaining = until - now
        return if (remaining > 0L) remaining else 0L
    }

    fun getFailedAttempts(key: String): Int = getAttempts(key)

    fun recordFailure(key: String, now: Long = monotonicNow()): Long {
        val count = getAttempts(key) + 1
        val delayMs = calculateDelayMs(count)
        val until = now + delayMs

        setAttempts(key, count)
        setLockUntil(key, until)
        setLong(key, KEY_LOCK_SINCE_PREFIX, memoryLockSince, now)
        setLong(key, KEY_LOCK_DELAY_PREFIX, memoryLockDelay, delayMs)
        return delayMs
    }

    fun recordSuccess(key: String) {
        reset(key)
    }

    fun reset(key: String) {
        memoryAttempts.remove(key)
        memoryLockUntil.remove(key)
        memoryLockSince.remove(key)
        memoryLockDelay.remove(key)
        prefs?.edit()?.apply {
            remove("$KEY_ATTEMPTS_PREFIX$key")
            remove("$KEY_LOCK_UNTIL_PREFIX$key")
            remove("$KEY_LOCK_SINCE_PREFIX$key")
            remove("$KEY_LOCK_DELAY_PREFIX$key")
            apply()
        }
    }

    fun resetAll() {
        memoryAttempts.clear()
        memoryLockUntil.clear()
        memoryLockSince.clear()
        memoryLockDelay.clear()
        prefs?.let { p ->
            val editor = p.edit()
            p.all.keys
                .filter {
                    it.startsWith(KEY_ATTEMPTS_PREFIX) || it.startsWith(KEY_LOCK_UNTIL_PREFIX) ||
                        it.startsWith(KEY_LOCK_SINCE_PREFIX) || it.startsWith(KEY_LOCK_DELAY_PREFIX)
                }
                .forEach { editor.remove(it) }
            editor.apply()
        }
    }

    private fun getAttempts(key: String): Int {
        val inMemory = memoryAttempts[key]
        if (inMemory != null) return inMemory
        val stored = prefs?.getInt("$KEY_ATTEMPTS_PREFIX$key", 0) ?: 0
        memoryAttempts[key] = stored
        return stored
    }

    private fun setAttempts(key: String, count: Int) {
        memoryAttempts[key] = count
        prefs?.edit()?.putInt("$KEY_ATTEMPTS_PREFIX$key", count)?.apply()
    }

    private fun getLockUntil(key: String): Long {
        val inMemory = memoryLockUntil[key]
        if (inMemory != null) return inMemory
        val stored = prefs?.getLong("$KEY_LOCK_UNTIL_PREFIX$key", 0L) ?: 0L
        memoryLockUntil[key] = stored
        return stored
    }

    private fun setLockUntil(key: String, until: Long) {
        memoryLockUntil[key] = until
        prefs?.edit()?.putLong("$KEY_LOCK_UNTIL_PREFIX$key", until)?.apply()
    }

    private fun getLong(key: String, prefix: String, cache: ConcurrentHashMap<String, Long>): Long {
        cache[key]?.let { return it }
        val stored = prefs?.getLong("$prefix$key", 0L) ?: 0L
        cache[key] = stored
        return stored
    }

    private fun setLong(key: String, prefix: String, cache: ConcurrentHashMap<String, Long>, value: Long) {
        cache[key] = value
        prefs?.edit()?.putLong("$prefix$key", value)?.apply()
    }
}
