package com.licht.nuvx.data.discord

import com.licht.nuvx.data.http.Http
import com.licht.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime





@OptIn(ExperimentalTime::class)
object DiscordBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private const val APPLICATION_ID = "1411019391843172514"
    private const val APP_URL = "https://nuvx.lol/open"
    private const val PROJECT_URL = "https://github.com/bxanedot/nuvx"

    fun interface TokenCallback {
        fun onResult(ok: Boolean, username: String?)
    }

    fun validateToken(token: String, callback: TokenCallback) {
        scope.launch {
            val user = runCatching {
                Http.getText(
                    "https://discord.com/api/v9/users/@me",
                    headers = mapOf(
                        "Authorization" to token,
                        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Discord/1.0",
                    ),
                )
            }.getOrNull()
            val name = user?.let {
                runCatching {
                    json.parseToJsonElement(it).jsonObject["username"]?.jsonPrimitive?.content
                }.getOrNull()
            }
            if (name != null) {
                AppSettings.setDiscordToken(token)
                AppSettings.setDiscordUsername(name)
            }
            callback.onResult(name != null, name)
        }
    }

    fun identifyPayload(token: String): String =
        """{"op":2,"d":{"token":${jsonStr(token)},"properties":{"os":"Mac OS X","browser":"Discord Client","device":""},"compress":false,"intents":0}}"""

    fun presencePayload(
        title: String,
        artist: String,
        album: String?,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
    ): String {
        val swap = AppSettings.discordSwapTitle.value
        val detailsRaw = if (swap) artist else title
        val stateRaw = if (swap) title else artist
        val now = Clock.System.now().toEpochMilliseconds()
        val speedSafe = if (speed <= 0.01f) 1f else speed
        val start = now - (positionMs / speedSafe).toLong()
        val end = if (durationMs > 0) start + (durationMs / speedSafe).toLong() else start + 1
        val details = if (speedSafe != 1f) "$detailsRaw [${speedSafe}x]" else detailsRaw
        val activityName = AppSettings.discordActivityName.value.ifBlank { "nuvx" }
        val activityType = when (AppSettings.discordActivityType.value.lowercase()) {
            "playing" -> 0
            "streaming" -> 1
            "watching" -> 3
            "competing" -> 5
            else -> 2
        }
        val status = AppSettings.discordStatus.value.ifBlank { "online" }
        val displayType = if (AppSettings.discordUseDetails.value) 2 else 1
        val buttons = buildList {
            if (AppSettings.discordButton1Visible.value) {
                add(resolveVariables(AppSettings.discordButton1Text.value.ifBlank { "Listen on nuvx" }, title, artist, album) to APP_URL)
            }
            if (AppSettings.discordButton2Visible.value) {
                add(resolveVariables(AppSettings.discordButton2Text.value.ifBlank { "Visit Nuvx" }, title, artist, album) to PROJECT_URL)
            }
        }
        val buttonJson = buttons.joinToString(",") { (label, url) ->
            """{"label":${jsonStr(label)},"url":${jsonStr(url)}}"""
        }
        val buttonPayload = if (buttons.isEmpty()) "" else ",\"buttons\":[$buttonJson],\"application_id\":\"$APPLICATION_ID\""
        return """{"op":3,"d":{"since":$now,"status":${jsonStr(status)},"afk":false,"activities":[{"name":${jsonStr(activityName)},"type":$activityType,"details":${jsonStr(details)},"details_url":${jsonStr(APP_URL)},"state":${jsonStr(stateRaw)},"status_display_type":$displayType,"timestamps":{"start":$start,"end":$end}$buttonPayload}]}}"""
    }

    private fun resolveVariables(text: String, title: String, artist: String, album: String?): String =
        text
            .replace("{song_name}", title)
            .replace("{artist_name}", artist)
            .replace("{album_name}", album.orEmpty())

    fun heartbeatPayload(seq: Long): String = """{"op":1,"d":$seq}"""

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
