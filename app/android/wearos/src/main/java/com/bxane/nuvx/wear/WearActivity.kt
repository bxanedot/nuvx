package com.bxane.nuvx.wear

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bxane.nuvx.wear.data.SelfUpdate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.wear.ambient.AmbientLifecycleObserver
import com.bxane.nuvx.wear.ui.Ambient
import com.bxane.nuvx.wear.ui.LocalAmbient
import com.bxane.nuvx.wear.data.PhoneLink
import com.bxane.nuvx.wear.ui.NuvxWearTheme
import com.bxane.nuvx.wear.ui.WearApp
import com.bxane.nuvx.ui.intro.NuvxIntro
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class WearViewModel(app: Application) : AndroidViewModel(app) {
    val link = PhoneLink(app, viewModelScope)

    override fun onCleared() = link.stop()
}

class WearActivity : ComponentActivity() {

    private val model: WearViewModel by viewModels()

    private val ambient = mutableStateOf(Ambient())

    private val ambientObserver by lazy {
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    ambient.value = Ambient(
                        active = true,
                        burnInProtection = ambientDetails.burnInProtectionRequired,
                        lowBit = ambientDetails.deviceHasLowBitAmbient,
                    )
                }

                override fun onUpdateAmbient() {
                    ambient.value = ambient.value.copy(tick = ambient.value.tick + 1)
                }

                override fun onExitAmbient() {
                    ambient.value = Ambient()
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = model.link.start()
                override fun onStop(owner: LifecycleOwner) = model.link.stop()
            },
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                SelfUpdate.pendingConfirm.filterNotNull().collect { confirm ->
                    SelfUpdate.pendingConfirm.value = null
                    runCatching { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        val playIntro = savedInstanceState == null && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        val thump = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        setContent {
            var showIntro by rememberSaveable { mutableStateOf(playIntro) }
            NuvxWearTheme {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalAmbient provides ambient.value) {
                        WearApp(model.link)
                    }
                    if (showIntro) NuvxIntro(
                        logo = ImageVector.vectorResource(R.drawable.ic_logo),
                        onBeat = { vibrator?.vibrate(thump) },
                        onFinished = { showIntro = false },
                    )
                }
            }
        }
    }
}
