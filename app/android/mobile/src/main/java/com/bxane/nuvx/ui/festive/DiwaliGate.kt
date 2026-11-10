package com.bxane.nuvx.ui.festive

import java.util.Calendar
import java.util.TimeZone

object DiwaliGate {
    private val INDIA_ZONES = setOf("Asia/Kolkata", "Asia/Calcutta")

    private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    private const val YEAR = 2026
    private const val MONTH = Calendar.NOVEMBER
    private const val DAY = 8

    fun isIndianTimezone(zone: TimeZone = TimeZone.getDefault()): Boolean = zone.id in INDIA_ZONES

    fun isDiwaliDay(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val c = Calendar.getInstance(IST).apply { timeInMillis = nowMillis }
        return c.get(Calendar.YEAR) == YEAR &&
            c.get(Calendar.MONTH) == MONTH &&
            c.get(Calendar.DAY_OF_MONTH) == DAY
    }
}
