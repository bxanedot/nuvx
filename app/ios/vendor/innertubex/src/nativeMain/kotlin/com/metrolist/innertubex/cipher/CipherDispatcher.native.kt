package com.metrolist.innertubex.cipher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.Foundation.NSThread
import kotlin.coroutines.CoroutineContext

private const val CIPHER_STACK_BYTES = 16_777_216uL



internal actual val cipherDispatcher: CoroutineDispatcher =
    object : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            NSThread { block.run() }.apply { stackSize = CIPHER_STACK_BYTES }.start()
        }
    }
