package com.bxane.nuvx.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

object SignedIn {

    @Volatile private var state: MutableStateFlow<Boolean>? = null

    fun state(context: Context): MutableStateFlow<Boolean> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(AuthStore(context.applicationContext).isSignedIn).also { state = it }
        }
}
