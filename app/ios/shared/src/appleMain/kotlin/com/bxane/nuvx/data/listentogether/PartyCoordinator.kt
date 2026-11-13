package com.bxane.nuvx.data.listentogether

import kotlin.concurrent.Volatile
import com.bxane.nuvx.BuildDefaults
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.settings.InstallId
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch




















































object PartyCoordinator {



    private val scope = CoroutineScope(SupervisorJob())


    private val client = HttpClient(Darwin)

    private val api = PartyClient(scope = scope, client = client, localNowMs = { localNowMs() })













    @Volatile
    var localNowMs: () -> Long = { 0L }










    @Volatile
    var onStateChanged: ((PartyState) -> Unit)? = null



    private val _session = PartySession()


    val session: PartySession get() = _session


    val state: StateFlow<PartyState> get() = _session.state

    private val _server = MutableStateFlow(ServerChoice("", ServerConnection.Unconfigured))
    val server: StateFlow<ServerChoice> = _server.asStateFlow()







    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()


    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var socketJob: Job? = null








    @Volatile
    var membership: PartyMembership? = null
        private set










    private fun configuredServer(): String = AppSettings.listenTogetherServer.value


    fun defaultServer(): String {
        val entered = configuredServer()
        return if (entered.isNotEmpty()) entered else BuildDefaults.LISTEN_TOGETHER_SERVER
    }








    fun serverProblem(raw: String): String? = when (val result = ServerUrl.parseAndNormalize(raw)) {
        is ServerUrlValidationResult.Valid ->
            if (result.normalizedUrl.isEmpty()) "Enter a server address to use Listen Together." else null

        is ServerUrlValidationResult.Invalid -> when (result.error) {
            ServerUrlError.Whitespace -> "That address contains a space."
            ServerUrlError.InvalidScheme -> "Only http and https addresses work."
            ServerUrlError.InvalidHost -> "That does not look like a server address."
            ServerUrlError.InvalidPort -> "That port number is not valid."
            ServerUrlError.InvalidPath -> "That address cannot contain a . or .. path."
            ServerUrlError.HasQuery -> "Remove the ? and everything after it."
            ServerUrlError.HasFragment -> "Remove the # and everything after it."
        }
    }








    suspend fun resolveServer() {
        val entered = configuredServer()
        val builtIn = BuildDefaults.LISTEN_TOGETHER_SERVER
        _busy.value = true
        publish()
        try {
            val choice = ServerSelection.resolve(entered, builtIn) { base, timeoutMs ->
                api.probe(base, timeoutMs)
            }
            _server.value = choice



            val settled = if (choice.base.isNotEmpty() && !choice.isFallback) choice.base else entered
            if (settled != entered) AppSettings.setListenTogetherServer(settled)
        } finally {
            _busy.value = false
            publish()
        }
    }








    @Throws(PartyException::class, CancellationException::class)
    suspend fun create(nickname: String, autoplay: Boolean): PartyMembership {
        val identity = identity(nickname)
        val result = call {
            api.create(base(), identity.userId, identity.deviceId, identity.displayName, identity.avatarUrl, autoplay)
        }
        join(result)
        return result
    }











    @Throws(PartyException::class, CancellationException::class)
    suspend fun join(code: String, nickname: String): PartyMembership {
        val cleaned = code.trim().uppercase()
        leave()
        val identity = identity(nickname)
        val result = call {
            api.join(base(), cleaned, identity.userId, identity.deviceId, identity.displayName, identity.avatarUrl)
        }
        join(result)
        return result
    }









    @Throws(PartyException::class, CancellationException::class)
    suspend fun joinInvite(link: String, nickname: String): PartyMembership {
        val invite = JamInviteLink.parseInvite(link)
            ?: throw PartyException(null, "bad_invite", "That is not a party invite.")
        val target = ServerSelection.switchTarget(
            inviteServer = invite.serverUrl,
            customServer = configuredServer(),
            idleServer = base(),
        )
        if (target.isNotEmpty()) {
            AppSettings.setListenTogetherServer(target)
            _server.value = ServerChoice(target, ServerConnection.Checking)
        }
        return join(invite.code, nickname)
    }










    @Throws(PartyException::class, CancellationException::class)
    suspend fun preview(code: String): PartyPreview = call {
        api.preview(base(), code.trim().uppercase())
    }









    @Throws(PartyException::class, CancellationException::class)
    suspend fun leave() {
        val current = membership
        val base = _session.serverBase
        disconnect()
        if (current == null) return
        call { api.leave(base, current.code, current.token) }
    }









    fun inviteLink(): String? {
        val code = membership?.code ?: return null
        val server = JamInviteLink.sanitizeServerUrl(_session.serverBase) ?: return null
        return "nuvx://party/$code?server=$server"
    }











    private fun join(party: PartyMembership) {
        disconnect()
        membership = party
        val base = party.serverBase()
        _session.begin(serverBase = base, code = party.code)
        publish()
        socketJob = scope.launch {
            PartySocketBridge.connect(base, party.code, party.token) { json ->
                onFrame(json)
            }
        }
    }










    private fun PartyMembership.serverBase(): String =
        ServerUrl.parseAndNormalize(_server.value.base).normalizedOrNull
            ?.takeIf { it.isNotEmpty() }
            ?: base()

    private fun onFrame(json: String) {
        val frame = PartyFrameCodec.decode(json) ?: return
        if (frame is PartyFrame.Pong) {
            _session.recordPong(frame.clientMs, frame.serverMs, localNowMs())
        }
        val applied = _session.apply(frame)
        if (applied is PartySession.Applied.Queue) _session.queueRefetchSent()
        if (applied is PartySession.Applied.Left) {



            membership = null
            socketJob = null
        }
        publish()
    }


    fun disconnect() {
        socketJob?.cancel()
        socketJob = null
        PartySocketBridge.stop()
        _session.reset()
        membership = null
        publish()
    }












    private fun send(frame: PartyOutgoing): Boolean {
        val state = _session.current
        if (!state.inParty) return false
        if (frame !is PartyOutgoing.Ping && frame !is PartyOutgoing.Report && !state.canControl) return false
        PartySocketBridge.sendRaw(PartyOutgoingCodec.encode(frame))
        return true
    }

    fun play(): Boolean = send(PartyOutgoingCodec.play())

    fun pause(): Boolean = send(PartyOutgoingCodec.pause())

    fun seek(positionMs: Long): Boolean = send(PartyOutgoingCodec.seek(positionMs))

    fun setTrack(track: PartyTrack): Boolean = send(PartyOutgoingCodec.setTrack(track))

    fun next(): Boolean = send(PartyOutgoingCodec.next())

    fun previous(): Boolean = send(PartyOutgoingCodec.previous())

    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false): Boolean =
        send(PartyOutgoingCodec.queueAdd(tracks, playNext))

    fun queueRemove(videoId: String): Boolean = send(PartyOutgoingCodec.queueRemove(videoId))

    fun queueClear(): Boolean = send(PartyOutgoingCodec.queueClear())

    fun queueMove(fromIndex: Int, toIndex: Int): Boolean =
        send(PartyOutgoingCodec.queueMove(fromIndex, toIndex))

    fun setQueue(items: List<PartyTrack>, queueIndex: Int): Boolean =
        send(PartyOutgoingCodec.setQueue(items, queueIndex))

    fun setAutoplay(enabled: Boolean): Boolean = send(PartyOutgoingCodec.setAutoplay(enabled))

    fun setHostOnlyControl(enabled: Boolean): Boolean =
        send(PartyOutgoingCodec.setHostOnlyControl(enabled))

    fun setMaxMembers(count: Int): Boolean = send(PartyOutgoingCodec.setMaxMembers(count))

    fun kick(memberId: String): Boolean = send(PartyOutgoingCodec.kick(memberId))


    fun report(positionMs: Long, isPlaying: Boolean) {
        PartySocketBridge.sendRaw(PartyOutgoingJson.report(positionMs, isPlaying))
    }


    fun askForState() {
        PartySocketBridge.sendRaw(PartyOutgoingJson.sync())
    }


    fun askForQueue() {
        PartySocketBridge.sendRaw(PartyOutgoingJson.syncQueue())
    }










    private fun base(): String {
        val resolved = _server.value.base
        if (resolved.isNotEmpty() && _server.value.connection != ServerConnection.Offline) return resolved
        val fallback = defaultServer()
        return ServerUrl.parseAndNormalize(fallback).normalizedOrNull.orEmpty()
    }








    private fun identity(nickname: String): PartyIdentity {
        val account = AppSettings.partyAccount.value
        return PartyIdentityFactory.resolve(
            deviceId = InstallId.get(),
            nickname = nickname,
            accountName = account?.name,
            accountEmail = account?.email,
            accountAvatarUrl = account?.avatarUrl,
        )
    }










    private suspend inline fun <T> call(crossinline block: suspend () -> T): T {
        _busy.value = true
        publish()
        return try {
            val value = block()
            _lastError.value = null
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: PartyException) {
            _lastError.value = e.message
            throw e
        } catch (e: Throwable) {
            val failure = PartyException(null, "transport", e.message ?: e.toString())
            _lastError.value = failure.message
            throw failure
        } finally {
            _busy.value = false
            publish()
        }
    }


    private fun publish() {
        onStateChanged?.invoke(_session.current)
    }


    fun shutdown() {
        disconnect()
        onStateChanged = null
        scope.cancel()
        client.close()
    }
}


private val ServerChoice.isFallback: Boolean get() = connection.isFallback
