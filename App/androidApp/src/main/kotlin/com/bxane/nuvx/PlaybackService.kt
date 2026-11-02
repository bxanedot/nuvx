@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.bxane.nuvx

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bxane.nuvx.core.resolveAudio
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val router = (application as NuvxApplication).router
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val source = ResolvingDataSource.Factory(OkHttpDataSource.Factory(client)) { spec ->
            if (spec.uri.scheme != "nuvx" || spec.uri.host != "track") throw IOException("Unsupported media request")
            // Media3 invokes this on its loading thread; never resolve a signed URL on the UI thread.
            val stream = try {
                runBlocking { withTimeout(45_000) { router.resolveAudio(spec.uri.lastPathSegment.orEmpty()) } }
            } catch (_: Exception) {
                throw IOException("Could not resolve this track. Check your connection or streaming engine.")
            }
            spec.withUri(Uri.parse(stream.url)).withRequestHeaders(spec.httpRequestHeaders + stream.headers)
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
