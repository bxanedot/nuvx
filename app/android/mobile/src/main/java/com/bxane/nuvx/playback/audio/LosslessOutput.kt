package com.bxane.nuvx.playback.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.playback.audio.bluetooth.BluetoothAudioTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

object LosslessOutput {

    enum class Kind { WIRED, USB, HDMI, DOCK, BLUETOOTH }

    data class State(
        val capable: Boolean = false,
        val via: Kind? = null,
        val bluetoothCodec: String? = null,
        val bluetoothCodecUnknown: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val capable: Boolean get() = _state.value.capable

    private var audioManager: AudioManager? = null
    private var tracker: BluetoothAudioTracker? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun init(context: Context) {
        if (audioManager != null) return
        val app = context.applicationContext
        val manager = app.getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        val bluetooth = BluetoothAudioTracker(app).also { it.start() }
        tracker = bluetooth
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            .launch { bluetooth.telemetry.collect { recompute() } }
        recompute()
    }

    fun refresh() {
        tracker?.refreshCurrentDevice()
        recompute()
    }

    private fun recompute() {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.isSink }
            .map { it.type }
            .toSet()
        val telemetry = tracker?.telemetry?.value
        val named = telemetry?.takeIf { it.hasNamedCodec }
        val next = decide(outputs, named?.codecName, named?.sampleRateHz)
        if (next != _state.value) {
            TrackLog.d(TAG, "lossless output: $next (outputs=$outputs, codecRate=${named?.sampleRateHz})")
            _state.value = next
        }
    }

    internal fun decide(outputTypes: Set<Int>, bluetoothCodec: String?, bluetoothSampleRateHz: Int? = null): State {
        val cabled = when {
            outputTypes.any { it in WIRED } -> Kind.WIRED
            outputTypes.any { it in USB } -> Kind.USB
            outputTypes.any { it in HDMI } -> Kind.HDMI
            outputTypes.any { it in DOCK } -> Kind.DOCK
            else -> null
        }
        if (cabled != null) return State(capable = true, via = cabled, bluetoothCodec = bluetoothCodec)
        if (AudioDeviceInfo.TYPE_BLUETOOTH_A2DP !in outputTypes) return State()
        if (bluetoothCodec == null) return State(bluetoothCodecUnknown = true)
        val lossless = isLosslessBluetoothCodec(bluetoothCodec, bluetoothSampleRateHz)
        return State(
            capable = lossless,
            via = Kind.BLUETOOTH.takeIf { lossless },
            bluetoothCodec = bluetoothCodec,
        )
    }

    internal fun isLosslessBluetoothCodec(name: String, sampleRateHz: Int? = null): Boolean {
        val codec = name.uppercase(Locale.ROOT)
        if ("LDAC" in codec || "LHDC" in codec || ("APTX" in codec && "LOSSLESS" in codec)) return true
        if ("UHQ" in codec) return true
        val samsung = SAMSUNG_NAMES.any { it in codec }
        return samsung && sampleRateHz != null && sampleRateHz >= 88_200
    }

    private val SAMSUNG_NAMES = listOf("SSC", "SAMSUNG", "SCALABLE", "SEAMLESS")

    private val WIRED = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
    )
    private val USB = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    private val HDMI = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        29,
    )
    private val DOCK = setOf(
        AudioDeviceInfo.TYPE_DOCK,
        31,
    )

    private const val TAG = "nuvx"
}
