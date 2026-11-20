package com.licht.nuvx.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRouteMemoryTest {
    private val memory = OutputRouteMemory()

    @Test
    fun devicesSeenAtRegistrationDoNotResetTheChoiceWhenReplayed() {
        memory.added(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, isSink = true)
        memory.added(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, isSink = true)
        // registerAudioDeviceCallback replays both at once
        assertFalse(memory.added(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, isSink = true))
        assertFalse(memory.added(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, isSink = true))
    }

    @Test
    fun inputsAndCallRoutesNeverCountAsANewOutput() {
        assertFalse(memory.added(3, AudioDeviceInfo.TYPE_BUILTIN_MIC, isSink = false))
        assertFalse(memory.added(4, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, isSink = true))
        assertFalse(memory.added(5, AudioDeviceInfo.TYPE_TELEPHONY, isSink = true))
    }

    @Test
    fun replugAfterRemovalAndRapidRepeatsCountOnce() {
        assertTrue(memory.added(7, AudioDeviceInfo.TYPE_USB_HEADSET, isSink = true))
        assertFalse(memory.added(7, AudioDeviceInfo.TYPE_USB_HEADSET, isSink = true))
        memory.removed(7)
        assertTrue(memory.added(7, AudioDeviceInfo.TYPE_USB_HEADSET, isSink = true))
    }

    @Test
    fun disconnectedSelectedBluetoothPausesButUnrelatedAndReconnectedDevicesDoNot() {
        val bluetooth = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val phone = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertTrue(shouldPauseForLostBluetooth(7, 7, bluetooth))
        assertFalse(shouldPauseForLostBluetooth(null, 7, bluetooth))
        assertFalse(shouldPauseForLostBluetooth(7, 8, bluetooth))
        assertFalse(shouldPauseForLostBluetooth(7, 7, phone))
        // After the router forgets a removed choice, an added output never
        // restores playWhenReady; only the user can explicitly resume.
        assertFalse(shouldPauseForLostBluetooth(null, 7, bluetooth))
    }

    @Test
    fun clearForgetsEverythingOnStop() {
        memory.added(9, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, isSink = true)
        memory.clear()
        assertTrue(memory.added(9, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, isSink = true))
    }
}
