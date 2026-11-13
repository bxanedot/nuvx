package com.bxane.nuvx.data.settings









expect object PlatformSettings {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)

















    fun getSecret(key: String): String?
    fun putSecret(key: String, value: String?)

    fun readSecret(key: String): SecretRead
    fun writeSecret(key: String, value: String?): Boolean
}

data class SecretRead(val value: String?, val available: Boolean)
