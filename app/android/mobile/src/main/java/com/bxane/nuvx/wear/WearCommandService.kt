package com.bxane.nuvx.wear

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.playback.ACTION_TOGGLE_FAVORITE
import com.bxane.nuvx.playback.ACTION_TOGGLE_SHUFFLE
import com.bxane.nuvx.playback.AudioRouting
import com.bxane.nuvx.playback.PlaybackService
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.bxane.nuvx.wear.WearProtocol as P

class WearCommandService : WearableListenerService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path ?: return
        if (!path.startsWith(P.PATH_COMMAND + "/")) return
        val command = path.substringAfterLast('/')
        val arg = event.data?.let { String(it, Charsets.UTF_8) }.orEmpty()
        WearBridge.onWatchSeen(this)

        when (command) {
            P.CMD_SYNC -> {
                WearBridge.sync(this)
                return
            }
            P.CMD_VOLUME -> {
                val steps = arg.toIntOrNull() ?: 0
                val speaker = WearBridge.speaker
                if (speaker?.active == true) main.post { speaker.nudgeVolume(steps) } else WearBridge.adjustVolume(this, steps)
                return
            }
            P.CMD_SPEAKER_STATUS -> {
                val map = event.data?.let { runCatching { DataMap.fromByteArray(it) }.getOrNull() } ?: return
                main.post { WearBridge.speaker?.onStatus(map) }
                return
            }
            P.CMD_OUTPUTS -> {
                WearBridge.publishOutputs(this)
                return
            }
            P.CMD_SELECT_OUTPUT -> {
                main.post {
                    AudioRouting.select(arg.toIntOrNull())
                    WearBridge.speaker?.stop(resumeHere = true)
                    WearBridge.publishOutputs(this)
                }
                return
            }
        }

        val done = CountDownLatch(1)
        Controllers.use(applicationContext, onMissing = { done.countDown() }) { controller ->
            runCatching { execute(controller, command, arg) }
                .onFailure { Log.w("NuvxWear", "command $command failed", it) }
            done.countDown()
        }
        done.await(8, TimeUnit.SECONDS)
    }

    private fun execute(controller: MediaController, command: String, arg: String) {
        if (controller.mediaItemCount == 0) {
            WearBridge.sync(this)
            return
        }
        when (command) {
            P.CMD_TOGGLE -> if (controller.playWhenReady) {
                controller.pause()
            } else {
                if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0L)
                controller.prepareIfIdle()
                controller.play()
            }
            P.CMD_NEXT -> {
                controller.seekToNextMediaItem()
                controller.prepareIfIdle()
            }
            P.CMD_PREVIOUS -> {
                controller.seekToPrevious()
                controller.prepareIfIdle()
            }
            P.CMD_LIKE -> controller.sendCustomCommand(
                SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY),
                Bundle.EMPTY,
            )
            P.CMD_SHUFFLE -> controller.sendCustomCommand(
                SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY),
                Bundle.EMPTY,
            )
            P.CMD_REPEAT -> {
                val next = when (controller.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                AppSettings.setRepeatMode(next)
                controller.repeatMode = next
            }
            P.CMD_SPEAKER_WATCH -> {
                WearBridge.speaker?.start()
                WearBridge.publishOutputs(this)
            }
            P.CMD_SEEK -> {
                val position = arg.toLongOrNull() ?: return
                controller.seekTo(position.coerceAtLeast(0L))
            }
            P.CMD_SKIP_TO -> {
                val index = arg.toIntOrNull()?.takeIf { it in 0 until controller.mediaItemCount } ?: return
                controller.seekToDefaultPosition(index)
                controller.prepareIfIdle()
                controller.play()
            }
            P.CMD_QUEUE -> {
                val current = controller.currentMediaItemIndex
                val from = (current - 1).coerceAtLeast(0)
                val to = (current + WearBridge.queueAhead).coerceAtMost(controller.mediaItemCount - 1)
                val rows = (from..to).map { i ->
                    val metadata = controller.getMediaItemAt(i).mediaMetadata
                    WearBridge.QueueRow(
                        index = i,
                        title = metadata.title?.toString().orEmpty(),
                        artist = metadata.artist?.toString().orEmpty(),
                        artworkUrl = metadata.artworkUri?.toString(),
                    )
                }
                val mediaId = controller.currentMediaItem?.mediaId
                Thread { WearBridge.publishQueue(applicationContext, rows, current, mediaId) }.start()
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != P.PATH_STREAM) return
        WearBridge.onWatchSeen(this)
        WearBridge.serveStream(this, channel) {
            Controllers.use(applicationContext, onMissing = {}) {}
        }
    }

    private fun MediaController.prepareIfIdle() {
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    private object Controllers {
        private const val IDLE_RELEASE_MS = 10_000L
        private const val GIVE_UP_MS = 7_000L

        private val main = Handler(Looper.getMainLooper())
        private var future: ListenableFuture<MediaController>? = null
        private val release = Runnable {
            future?.let(MediaController::releaseFuture)
            future = null
        }

        fun use(app: Context, onMissing: () -> Unit, block: (MediaController) -> Unit) {
            main.post {
                main.removeCallbacks(release)
                val pending = future?.takeUnless { it.isDone && it.connected() == null }
                    ?: MediaController.Builder(
                        app,
                        SessionToken(app, ComponentName(app, PlaybackService::class.java)),
                    ).buildAsync().also { future = it }
                var ran = false
                pending.addListener(
                    {
                        if (ran) return@addListener
                        ran = true
                        val controller = pending.connected()
                        if (controller == null) {
                            future = null
                            onMissing()
                        } else {
                            block(controller)
                        }
                        main.postDelayed(release, IDLE_RELEASE_MS)
                    },
                    ContextCompat.getMainExecutor(app),
                )
                main.postDelayed({ if (!ran) { ran = true; onMissing() } }, GIVE_UP_MS)
            }
        }

        private fun ListenableFuture<MediaController>.connected(): MediaController? =
            runCatching { get() }.getOrNull()?.takeIf { it.isConnected }
    }
}
