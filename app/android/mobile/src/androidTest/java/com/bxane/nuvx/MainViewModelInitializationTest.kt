package com.bxane.nuvx

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bxane.nuvx.auth.SignedIn
import com.bxane.nuvx.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainViewModelInitializationTest {
    @Test
    fun signedInInitializationLoadsLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val signedIn = SignedIn.state(context)
        val wasSignedIn = signedIn.value
        val store = ViewModelStore()
        signedIn.value = true
        try {
            val viewModel = MainViewModel(application)
            store.put("main", viewModel)
            assertTrue(viewModel.signedIn.value)
        } finally {
            store.clear()
            signedIn.value = wasSignedIn
        }
    }
}
