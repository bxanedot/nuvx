package com.bxane.nuvx.data.listentogether

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
























class PartySession {

    private val _state = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = _state.asStateFlow()

    val current: PartyState get() = _state.value





    private val clock = ServerClock()


    fun serverClock(): ServerClock = clock













    fun recordPong(sentAtLocalMs: Long, serverMs: Long, receivedAtLocalMs: Long) {
        clock.record(sentAtLocalMs, serverMs, receivedAtLocalMs)
        _state.update {
            it.copy(clockSynced = clock.synced, roundTripMs = clock.roundTripMs)
        }
    }


    fun correctedPosition(playback: PartyPlayback, localNowMs: Long): Long =
        clock.positionFor(playback.positionMs, playback.anchorMs, localNowMs)









    @Volatile
    private var refetchQueued = false











    @Volatile
    private var appliedSeq: Long? = null


    @Volatile
    var serverBase: String = ""
        private set

    @Volatile
    var code: String = ""
        private set


    sealed interface Applied {
        data object Nothing : Applied
        data object Members : Applied
        data object Queue : Applied
        data object Playback : Applied
        data object Left : Applied
    }

    fun begin(serverBase: String, code: String) {
        this.serverBase = serverBase
        this.code = code
        appliedSeq = null
        refetchQueued = false



        _state.value = PartyState(inParty = true, connection = PartyConnection.CONNECTING)
    }

    fun reset() {
        serverBase = ""
        code = ""
        appliedSeq = null
        refetchQueued = false
        clock.reset()
        _state.value = PartyState()
    }

    companion object {







        const val ACTIVITY_LIMIT = 100
    }








    fun apply(frame: PartyFrame): Applied = when (frame) {
        is PartyFrame.Welcome -> {
            _state.update {
                it.copy(
                    inParty = true,
                    connection = PartyConnection.LIVE,
                    you = frame.you,
                    members = frame.party.members,
                    maxMembers = frame.party.maxMembers,
                    hostOnlyControl = frame.party.hostOnlyControl,
                    playback = frame.party.playback,
                    queue = frame.party.queue,
                    lastServerMs = frame.serverMs,



                    error = null,
                )
            }
            refetchQueued = false
            appliedSeq = frame.party.playback.seq
            Applied.Members
        }

        is PartyFrame.State -> applyState(frame)
        is PartyFrame.Queue -> {



            refetchQueued = false
            _state.update { it.copy(queue = frame.queue, lastServerMs = frame.serverMs, needsQueueRefetch = false) }
            Applied.Queue
        }

        is PartyFrame.Members -> {
            _state.update {
                it.copy(
                    members = frame.members,
                    maxMembers = frame.maxMembers,
                    hostOnlyControl = frame.hostOnlyControl,
                    lastServerMs = frame.serverMs,
                )
            }
            refetchQueued = false
            Applied.Members
        }

        is PartyFrame.Activity -> {









            val fresh = PartyActivity(frame.action, frame.by, frame.atMs, frame.detail)
            val existing = _state.value.activities
            _state.update {
                it.copy(
                    activity = fresh,
                    activities = (listOf(fresh) + existing.filterNot { entry -> entry == fresh }).take(ACTIVITY_LIMIT),
                )
            }
            Applied.Nothing
        }

        is PartyFrame.Failure -> {
            _state.update { it.copy(error = PartyError(frame.error, frame.message)) }
            Applied.Nothing
        }

        is PartyFrame.Bye -> {



            _state.update {
                it.copy(
                    inParty = false,
                    connection = PartyConnection.OFFLINE,
                    error = PartyError("left", frame.reason),
                )
            }
            Applied.Left
        }

        is PartyFrame.Pong -> {
            _state.update { it.copy(lastServerMs = frame.serverMs) }
            Applied.Nothing
        }
    }

    private fun applyState(frame: PartyFrame.State): Applied {
        val incoming = frame.playback
        val held = _state.value
        val applied = appliedSeq




        val staleQueue = incoming.queueSeq > held.queue.seq && !refetchQueued
        if (staleQueue) refetchQueued = true








        val isNewer = applied == null || incoming.seq > applied
        val playback = if (isNewer) incoming else held.playback.withPartyFactsFrom(incoming)







        _state.update {
            it.copy(
                playback = playback,
                lastServerMs = frame.serverMs,
                needsQueueRefetch = staleQueue,
                error = null,
            )
        }
        if (isNewer) appliedSeq = incoming.seq
        return when {
            staleQueue -> Applied.Queue
            isNewer -> Applied.Playback
            else -> Applied.Nothing
        }
    }


    fun queueRefetchSent() {
        refetchQueued = false
        _state.update { it.copy(needsQueueRefetch = false) }
    }
}


enum class PartyConnection {

    OFFLINE,


    CONNECTING,


    LIVE,
}


data class PartyState(
    val inParty: Boolean = false,









    val you: PartyMember? = null,
    val members: List<PartyMember> = emptyList(),
    val maxMembers: Int = 5,
    val hostOnlyControl: Boolean = false,
    val playback: PartyPlayback = PartyPlayback(),
    val queue: PartyQueue = PartyQueue(),
    val activity: PartyActivity? = null,






    val activities: List<PartyActivity> = emptyList(),
    val error: PartyError? = null,
    val connection: PartyConnection = PartyConnection.OFFLINE,

    val clockSynced: Boolean = false,

    val roundTripMs: Long = 0,

    val lastServerMs: Long = 0,

    val needsQueueRefetch: Boolean = false,
) {
    val host: PartyMember? get() = members.firstOrNull { it.isHost }

    val isHost: Boolean get() = you?.isHost == true







    val myMemberId: String get() = you?.memberId.orEmpty()


    fun isMe(member: PartyMember): Boolean = myMemberId.isNotEmpty() && myMemberId == member.memberId









    val canControl: Boolean get() = isHost || !hostOnlyControl












    val controlsLocked: Boolean get() = inParty && !canControl








    val hasRoom: Boolean get() = members.size < maxMembers

    val isFull: Boolean get() = !hasRoom


    val hostName: String get() = host?.name.orEmpty()


    fun isFullFor(joining: Boolean): Boolean = joining && isFull
}


data class PartyError(val code: String, val message: String)
