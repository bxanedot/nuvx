package com.bxane.nuvx.data.listentogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
















@Serializable
sealed interface PartyFrame {


    val serverMs: Long


    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val you: PartyMember,
        val party: PartySnapshot,
        override val serverMs: Long = 0,
    ) : PartyFrame


    @Serializable
    @SerialName("state")
    data class State(
        val playback: PartyPlayback = PartyPlayback(),
        override val serverMs: Long = 0,
    ) : PartyFrame


    @Serializable
    @SerialName("queue")
    data class Queue(
        val queue: PartyQueue = PartyQueue(),
        override val serverMs: Long = 0,
    ) : PartyFrame









    @Serializable
    @SerialName("members")
    data class Members(
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        val hostOnlyControl: Boolean = false,
        override val serverMs: Long = 0,
    ) : PartyFrame





    @Serializable
    @SerialName("pong")
    data class Pong(
        val clientMs: Long = 0,
        override val serverMs: Long = 0,
    ) : PartyFrame


    @Serializable
    @SerialName("error")
    data class Failure(
        val error: String = "",
        val message: String = "",
        override val serverMs: Long = 0,
    ) : PartyFrame









    @Serializable
    @SerialName("bye")
    data class Bye(
        val reason: String = "",
        override val serverMs: Long = 0,
    ) : PartyFrame


    @Serializable
    @SerialName("activity")
    data class Activity(
        val action: String = "",
        val by: String = "",
        val detail: String = "",
        val atMs: Long = 0,
        override val serverMs: Long = 0,
    ) : PartyFrame
}


sealed interface PartyOutgoing {








    data class Ping(val clientMs: Long) : PartyOutgoing


    data object Sync : PartyOutgoing


    data object SyncQueue : PartyOutgoing








    data class Report(val positionMs: Long, val isPlaying: Boolean) : PartyOutgoing


    data class Control(val action: String, val payload: JsonObject) : PartyOutgoing

    companion object {
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val SEEK = "seek"
        const val SET_TRACK = "setTrack"
        const val SET_QUEUE = "setQueue"
        const val QUEUE_ADD = "queueAdd"
        const val QUEUE_REMOVE = "queueRemove"
        const val QUEUE_CLEAR = "queueClear"
        const val QUEUE_MOVE = "queueMove"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val KICK = "kick"
        const val SET_MAX_MEMBERS = "setMaxMembers"
        const val SET_AUTOPLAY = "setAutoplay"
        const val SET_HOST_ONLY_CONTROL = "setHostOnlyControl"
    }
}


object PartyOutgoingCodec {

    fun encode(frame: PartyOutgoing): String = when (frame) {
        is PartyOutgoing.Ping -> buildJsonObject {
            put("type", "ping")
            put("clientMs", frame.clientMs)
        }.toString()

        PartyOutgoing.Sync -> buildJsonObject { put("type", "sync") }.toString()
        PartyOutgoing.SyncQueue -> buildJsonObject { put("type", "syncQueue") }.toString()

        is PartyOutgoing.Report -> buildJsonObject {
            put("type", "report")
            put("positionMs", frame.positionMs)
            put("isPlaying", frame.isPlaying)
        }.toString()

        is PartyOutgoing.Control -> buildJsonObject {
            put("type", "control")
            put("action", frame.action)




            frame.payload.forEach { (key, value) -> put(key, value) }
        }.toString()
    }







    fun play() = control(PartyOutgoing.PLAY, buildJsonObject { })

    fun pause() = control(PartyOutgoing.PAUSE, buildJsonObject { })

    fun seek(positionMs: Long) = control(PartyOutgoing.SEEK, buildJsonObject {
        put("positionMs", positionMs)
    })

    fun setTrack(track: PartyTrack) = control(PartyOutgoing.SET_TRACK, buildJsonObject {
        put("track", track.toJson())
    })

    fun setQueue(items: List<PartyTrack>, queueIndex: Int) = control(PartyOutgoing.SET_QUEUE, buildJsonObject {
        put("queue", kotlinx.serialization.json.buildJsonArray {
            items.forEach { add(it.toJson()) }
        })
        put("queueIndex", queueIndex)
    })

    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false) =
        control(PartyOutgoing.QUEUE_ADD, buildJsonObject {



            if (tracks.size == 1) {
                put("track", tracks.first().toJson())
            } else {
                put("tracks", kotlinx.serialization.json.buildJsonArray { tracks.forEach { add(it.toJson()) } })
            }
            put("playNext", playNext)
        })








    fun queueRemove(videoId: String) = control(PartyOutgoing.QUEUE_REMOVE, buildJsonObject {
        put("videoId", videoId)
    })

    fun queueClear() = control(PartyOutgoing.QUEUE_CLEAR, buildJsonObject { })

    fun queueMove(fromIndex: Int, toIndex: Int) = control(PartyOutgoing.QUEUE_MOVE, buildJsonObject {
        put("fromIndex", fromIndex)
        put("toIndex", toIndex)
    })

    fun next() = control(PartyOutgoing.NEXT, buildJsonObject { })

    fun previous() = control(PartyOutgoing.PREVIOUS, buildJsonObject { })

    fun kick(memberId: String) = control(PartyOutgoing.KICK, buildJsonObject {
        put("memberId", memberId)
    })

    fun setMaxMembers(count: Int) = control(PartyOutgoing.SET_MAX_MEMBERS, buildJsonObject {
        put("maxMembers", count)
    })

    fun setAutoplay(enabled: Boolean) = control(PartyOutgoing.SET_AUTOPLAY, buildJsonObject {
        put("enabled", enabled)
    })

    fun setHostOnlyControl(enabled: Boolean) = control(PartyOutgoing.SET_HOST_ONLY_CONTROL, buildJsonObject {
        put("enabled", enabled)
    })


    private fun control(action: String, payload: JsonObject) = PartyOutgoing.Control(action, payload)
}

internal fun PartyTrack.toJson(): JsonObject = buildJsonObject {
    put("videoId", videoId)
    put("title", title)
    put("artist", artist)
    thumbnailUrl?.let { put("thumbnailUrl", it) }
    durationMs?.let { put("durationMs", it) }
    put("fromAutoplay", fromAutoplay)
}


object PartyFrameCodec {

    private val json = Json {
        ignoreUnknownKeys = true


        isLenient = true
        coerceInputValues = true
        classDiscriminator = "type"
    }









    fun decode(text: String): PartyFrame? {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null



        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        return runCatching {
            when (type) {
                "welcome" -> json.decodeFromJsonElement(PartyFrame.Welcome.serializer(), obj)
                "state" -> json.decodeFromJsonElement(PartyFrame.State.serializer(), obj)
                "queue" -> json.decodeFromJsonElement(PartyFrame.Queue.serializer(), obj)
                "members" -> json.decodeFromJsonElement(PartyFrame.Members.serializer(), obj)
                "pong" -> json.decodeFromJsonElement(PartyFrame.Pong.serializer(), obj)
                "error" -> json.decodeFromJsonElement(PartyFrame.Failure.serializer(), obj)
                "bye" -> json.decodeFromJsonElement(PartyFrame.Bye.serializer(), obj)
                "activity" -> json.decodeFromJsonElement(PartyFrame.Activity.serializer(), obj)
                else -> null
            }
        }.getOrNull()
    }
}









object PartyOutgoingJson {


    fun ping(clientMs: Long): String = PartyOutgoingCodec.encode(PartyOutgoing.Ping(clientMs))

    fun sync(): String = PartyOutgoingCodec.encode(PartyOutgoing.Sync)

    fun syncQueue(): String = PartyOutgoingCodec.encode(PartyOutgoing.SyncQueue)

    fun report(positionMs: Long, isPlaying: Boolean): String =
        PartyOutgoingCodec.encode(PartyOutgoing.Report(positionMs, isPlaying))
}
