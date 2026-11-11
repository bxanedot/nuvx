package com.bxane.nuvx.data.listentogether

import android.content.Context
import android.os.SystemClock
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import com.bxane.nuvx.BuildConfig
import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.settings.AppSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import java.util.concurrent.TimeUnit

sealed interface ServerUrlError {
    data object Whitespace : ServerUrlError
    data object InvalidScheme : ServerUrlError
    data object InvalidHost : ServerUrlError
    data object InvalidPort : ServerUrlError
    data object InvalidPath : ServerUrlError
    data object HasPath : ServerUrlError
    data object HasQuery : ServerUrlError
    data object HasFragment : ServerUrlError
    data object Malformed : ServerUrlError
}

sealed interface ServerUrlValidationResult {
    data class Valid(val normalizedUrl: String) : ServerUrlValidationResult
    data class Invalid(val error: ServerUrlError) : ServerUrlValidationResult
}

sealed interface ServerConnectionState {
    data object Checking : ServerConnectionState
    data class DefaultOnline(val latencyMs: Long) : ServerConnectionState
    data class CustomOnline(val latencyMs: Long) : ServerConnectionState
    data class CustomFallback(val latencyMs: Long) : ServerConnectionState
    data object Offline : ServerConnectionState
}

data class ProbeResult(val isOnline: Boolean, val latencyMs: Long = 0L)

val ServerConnectionState.latencyMs: Long?
    get() = when (this) {
        is ServerConnectionState.DefaultOnline -> latencyMs
        is ServerConnectionState.CustomOnline -> latencyMs
        is ServerConnectionState.CustomFallback -> latencyMs
        ServerConnectionState.Checking, ServerConnectionState.Offline -> null
    }

val ServerConnectionState.isFallback: Boolean
    get() = this is ServerConnectionState.CustomFallback

val ServerConnectionState.health: ListenTogether.Health
    get() = when (this) {
        ServerConnectionState.Checking -> ListenTogether.Health.CHECKING
        is ServerConnectionState.DefaultOnline,
        is ServerConnectionState.CustomOnline,
        is ServerConnectionState.CustomFallback -> ListenTogether.Health.ONLINE
        ServerConnectionState.Offline -> ListenTogether.Health.OFFLINE
    }

object ListenTogether {

    enum class Connection { OFFLINE, CONNECTING, LIVE }

    data class State(
        val code: String? = null,
        val you: PartyMember? = null,
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        val hostOnlyControl: Boolean = false,
        val playback: PartyPlayback = PartyPlayback(),
        val queue: PartyQueue = PartyQueue(),
        val connection: Connection = Connection.OFFLINE,
        val clockSynced: Boolean = false,
        val roundTripMs: Long = 0,
        val error: String? = null,
    ) {
        val inParty: Boolean get() = code != null
        val isFull: Boolean get() = members.size >= maxMembers

        val controlsLocked: Boolean
            get() = inParty && hostOnlyControl && you?.isHost != true
    }

    class PartyException(val code: String, message: String, val statusCode: Int? = null) : Exception(message)

    sealed interface SwitchPartyResult {
        data class Success(val partyCode: String) : SwitchPartyResult
        data class TargetFailedRecovered(val partyCode: String, val targetError: String) : SwitchPartyResult
        data class TargetFailedNoParty(val targetError: String) : SwitchPartyResult
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                readTimeout(0, TimeUnit.MILLISECONDS)
                connectTimeout(15, TimeUnit.SECONDS)
                pingInterval(20, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout)
        install(WebSockets)
        expectSuccess = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = ServerClock(SystemClock::elapsedRealtime)
    private val switchMutex = Mutex()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _activity = MutableStateFlow<List<PartyActivity>>(emptyList())
    val activity: StateFlow<List<PartyActivity>> = _activity.asStateFlow()

    fun parseAndNormalizeServerUrl(raw: String): ServerUrlValidationResult =
        ListenTogetherServerUrl.parseAndNormalizeServerUrl(raw)

    private val _customServer = MutableStateFlow("")
    val customServerUrl: StateFlow<String> = _customServer.asStateFlow()

    val defaultServer: String = when (val res = parseAndNormalizeServerUrl(BuildConfig.LISTEN_TOGETHER_SERVER)) {
        is ServerUrlValidationResult.Valid -> res.normalizedUrl
        is ServerUrlValidationResult.Invalid -> error("Invalid BuildConfig.LISTEN_TOGETHER_SERVER: ${BuildConfig.LISTEN_TOGETHER_SERVER}")
    }

    private val _effectiveIdleServer = MutableStateFlow(defaultServer)
    fun effectiveIdleServerBase(): String = _effectiveIdleServer.value

    fun resolveSwitchTarget(
        inviteServer: String?,
        customServer: String,
        idleServer: String = effectiveIdleServerBase(),
    ): String = inviteServer ?: customServer.ifBlank { idleServer }

    @Volatile
    private var activePartyServerBase: String? = null
    fun activePartyServerBase(): String? = activePartyServerBase

    val isUsingDefaultFallback: Boolean
        get() {
            val configured = _customServer.value
            return configured.isNotBlank() &&
                effectiveIdleServerBase() == defaultServer &&
                configured != defaultServer
        }

    val hasServer: Boolean get() = effectiveIdleServerBase().isNotBlank()

    enum class Health { UNKNOWN, CHECKING, ONLINE, OFFLINE }

    data class ServerStatus(
        val health: Health = Health.UNKNOWN,
        val latencyMs: Long = 0,
        val isFallback: Boolean = false,
    )

    private val _serverConnectionState = MutableStateFlow<ServerConnectionState>(ServerConnectionState.Checking)
    val serverConnectionState: StateFlow<ServerConnectionState> = _serverConnectionState.asStateFlow()

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    fun isEligibleForFallback(error: Throwable): Boolean =
        ListenTogetherServerHealth.isEligibleForFallback(error)

    const val CUSTOM_SERVER_TIMEOUT_MS = 6_000L
    const val DEFAULT_SERVER_TIMEOUT_MS = 30_000L

    private var isScreenActive: Boolean = false
    private var healthMonitorJob: Job? = null
    private var lastHealthProbeMs = 0L
    private val resolutionMutex = Mutex()
    private var activeResolutionJob: Job? = null
    private var resolutionGeneration = 0L

    fun setScreenActive(active: Boolean) {
        isScreenActive = active
        if (active && !shouldSkipIdleProbe(
                screenActive = true,
                connectionLive = _state.value.connection == Connection.LIVE,
                inParty = _state.value.code != null,
                metered = runCatching { AppSettings.meteredConnection.value }.getOrNull(),
            )
        ) {
            refreshServerHealth(showChecking = false)
        }
    }

    internal fun shouldSkipIdleProbe(
        screenActive: Boolean,
        connectionLive: Boolean,
        inParty: Boolean,
        metered: Boolean?,
    ): Boolean = ListenTogetherServerHealth.shouldSkipIdleProbe(
        screenActive = screenActive,
        connectionLive = connectionLive,
        inParty = inParty,
        metered = metered,
    )

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive) {
                delay(HEALTH_POLL_INTERVAL_MS)
                val snapshot = _state.value
                if (!shouldSkipIdleProbe(
                        screenActive = isScreenActive,
                        connectionLive = snapshot.connection == Connection.LIVE,
                        inParty = snapshot.code != null,
                        metered = runCatching { AppSettings.meteredConnection.value }.getOrNull(),
                    )
                ) {
                    refreshServerHealth(showChecking = false)
                }
            }
        }
    }

    suspend fun resolveServerConnection(
        customServer: String,
        defaultServer: String,
        probeCustom: suspend () -> ProbeResult,
        probeDefault: suspend () -> ProbeResult,
    ): Pair<String, ServerConnectionState> = ListenTogetherServerHealth.resolveServerConnection(
        customServer = customServer,
        defaultServer = defaultServer,
        probeCustom = probeCustom,
        probeDefault = probeDefault,
    )

    private suspend fun resolveServerConnectionSerialized(
        showChecking: Boolean = false,
        targetGeneration: Long? = null,
    ): ServerConnectionState {
        if (showChecking) {
            resolutionMutex.withLock {
                if (targetGeneration == null || targetGeneration == resolutionGeneration) {
                    _serverConnectionState.value = ServerConnectionState.Checking
                    _serverStatus.value = ServerStatus(Health.CHECKING)
                }
            }
        }

        val custom = _customServer.value
        val (effectiveServer, state) = resolveServerConnection(
            customServer = custom,
            defaultServer = defaultServer,
            probeCustom = { probeHealthWithLatency(custom, CUSTOM_SERVER_TIMEOUT_MS) },
            probeDefault = { probeHealthWithLatency(defaultServer, DEFAULT_SERVER_TIMEOUT_MS) },
        )

        resolutionMutex.withLock {
            if (targetGeneration == null || targetGeneration == resolutionGeneration) {
                _effectiveIdleServer.value = effectiveServer
                _serverConnectionState.value = state
                _serverStatus.value = ServerStatus(
                    health = state.health,
                    latencyMs = state.latencyMs ?: 0L,
                    isFallback = state.isFallback,
                )
            }
        }
        return state
    }

    fun refreshServerHealth(showChecking: Boolean = false) {
        val now = clock.nowMs()
        if (!showChecking && now - lastHealthProbeMs < HEALTH_MIN_GAP_MS) return
        lastHealthProbeMs = now
        scope.launch {
            val (job, _) = resolutionMutex.withLock {
                activeResolutionJob?.cancel()
                val nextGen = ++resolutionGeneration
                val newJob = scope.async(start = CoroutineStart.LAZY) {
                    resolveServerConnectionSerialized(showChecking = showChecking, targetGeneration = nextGen)
                }
                activeResolutionJob = newJob
                Pair(newJob, nextGen)
            }
            job.start()
        }
    }

    suspend fun probeHealthWithLatency(serverUrl: String, timeoutMs: Long): ProbeResult {
        val raw = resolveHttpBase(serverUrl)
        if (raw.isBlank()) return ProbeResult(isOnline = false, latencyMs = 0L)
        val start = clock.nowMs()
        val isOnline = runCatching {
            val response = http.get("$raw/healthz") {
                timeout { requestTimeoutMillis = timeoutMs }
            }
            if (!response.status.isSuccess()) return@runCatching false
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            body.contains("\"ok\":true")
        }.getOrElse {
            Log.w(TAG, "health check failed for ${redact(raw)}: ${redact(it.message)}")
            false
        }
        val elapsed = clock.nowMs() - start
        return ProbeResult(isOnline = isOnline, latencyMs = if (isOnline) elapsed.coerceAtLeast(0L) else 0L)
    }

    data class HealthResolution(
        val resolvedServer: String,
        val health: Health,
        val isFallback: Boolean,
    )

    suspend fun computeHealthResolution(
        customServer: String,
        probeCustom: suspend () -> Boolean,
        probeDefault: suspend () -> Boolean,
    ): HealthResolution = ListenTogetherServerHealth.computeHealthResolution(
        customServer = customServer,
        defaultServer = defaultServer,
        probeCustom = probeCustom,
        probeDefault = probeDefault,
    )

    private lateinit var prefs: SharedPreferences
    private val kickLedger = PartyKickLedger(
        preferences = { prefs },
        json = json,
        key = KEY_KICKED,
        blockMs = KICK_BLOCK_MS,
    )
    private val identityResolver = PartyIdentityResolver(
        preferences = { prefs },
        defaultNickname = ::nickname,
        deviceIdKey = KEY_DEVICE,
        avatarSizePx = AVATAR_PX,
    )
    private var token: String? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null
    private var socketJob: Job? = null
    private val frameHandler = PartySocketFrameHandler(
        json = json,
        clock = clock,
        scope = scope,
        state = { _state.value },
        updateState = { update -> _state.update(update) },
        send = ::send,
        recordActivity = ::recordActivity,
        recordKick = ::recordKick,
        leaveParty = { leaveParty() },
        redact = ::redact,
        tag = TAG,
    )
    private val socketConnection = PartySocketConnection(
        scope = scope,
        http = http,
        partyCode = { _state.value.code },
        token = { token },
        state = { _state.value },
        updateState = { update -> _state.update(update) },
        clock = clock,
        socketBase = ::wsBase,
        setSession = { session = it },
        onFrame = frameHandler::onFrame,
        partyPositionMs = ::partyPositionMs,
        redact = ::redact,
        tag = TAG,
    )

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawSaved = prefs.getString(KEY_SERVER, null)?.trim().orEmpty()
        val normalizedSaved = when (val res = parseAndNormalizeServerUrl(rawSaved)) {
            is ServerUrlValidationResult.Valid -> res.normalizedUrl
            is ServerUrlValidationResult.Invalid -> ""
        }
        _customServer.value = normalizedSaved
        _effectiveIdleServer.value = if (normalizedSaved.isNotBlank()) {
            normalizedSaved
        } else {
            defaultServer
        }

        val code = prefs.getString(KEY_CODE, null)
        val saved = prefs.getString(KEY_TOKEN, null)
        prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
        if (!code.isNullOrBlank() && !saved.isNullOrBlank()) {
            releaseStaleSlot(code, saved)
        }

        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager != null) {
            runCatching {
                manager.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            val snapshot = _state.value
                            if (!shouldSkipIdleProbe(
                                    screenActive = isScreenActive,
                                    connectionLive = snapshot.connection == Connection.LIVE,
                                    inParty = snapshot.code != null,
                                    metered = runCatching { AppSettings.meteredConnection.value }.getOrNull(),
                                )
                            ) {
                                refreshServerHealth(showChecking = false)
                            }
                        }
                        override fun onLost(network: Network) {
                            _serverConnectionState.value = ServerConnectionState.Offline
                            _serverStatus.value = ServerStatus(Health.OFFLINE)
                        }
                    }
                )
            }
        }

        startHealthMonitor()
        refreshServerHealth(showChecking = true)
    }

    private fun releaseStaleSlotOnServer(serverBase: String, code: String, held: String) {
        if (serverBase.isBlank() || code.isBlank() || held.isBlank()) return
        scope.launch {
            runCatching {
                http.post("$serverBase/api/parties/$code/leave") {
                    header("Authorization", "Bearer $held")
                }
            }.onFailure { failure ->
                Log.i(TAG, "stale party slot left to the server's grace: ${redact(failure.message)}")
            }
        }
    }

    private fun releaseStaleSlot(code: String, held: String) {
        val server = activePartyServerBase ?: resolveHttpBase(effectiveIdleServerBase())
        releaseStaleSlotOnServer(server, code, held)
    }

    suspend fun setCustomServerUrl(normalizedUrl: String): ServerConnectionState {
        require(parseAndNormalizeServerUrl(normalizedUrl) == ServerUrlValidationResult.Valid(normalizedUrl)) {
            "setCustomServerUrl accepts only canonical URLs produced by parseAndNormalizeServerUrl"
        }

        prefs.edit().putString(KEY_SERVER, normalizedUrl).apply()
        _customServer.value = normalizedUrl

        val (job, _) = resolutionMutex.withLock {
            activeResolutionJob?.cancel()
            val nextGen = ++resolutionGeneration
            val newJob = scope.async(start = CoroutineStart.LAZY) {
                resolveServerConnectionSerialized(showChecking = true, targetGeneration = nextGen)
            }
            activeResolutionJob = newJob
            Pair(newJob, nextGen)
        }
        job.start()
        return job.await()
    }

    fun canJoin(): Boolean = identityResolver.resolve() != null

    fun nickname(): String = prefs.getString(KEY_NICKNAME, null).orEmpty()

    fun setNickname(value: String) {
        prefs.edit().putString(KEY_NICKNAME, value.trim().take(80)).apply()
    }

    fun ensureConnected() {
        if (_state.value.code == null || token == null) return
        if (socketJob?.isActive == true) return
        connect()
    }

    suspend fun createParty(nickname: String = nickname(), maxMembers: Int = 5): Result<String> = switchMutex.withLock {
        val primary = effectiveIdleServerBase()
        val primaryNormalized = resolveHttpBase(primary)
        val attempt = runCatching {
            doCreateOnServer(primaryNormalized, nickname, maxMembers)
        }

        if (attempt.isSuccess) {
            return@withLock Result.success(attempt.getOrThrow())
        }

        val failure = attempt.exceptionOrNull() ?: return@withLock Result.failure(IllegalStateException("Unknown create failure"))

        if (normalizeServerBase(primary) != defaultServer && isEligibleForFallback(failure)) {
            Log.w(TAG, "createParty failed on custom server, falling back to default: ${redact(failure.message)}")
            val fallbackNormalized = resolveHttpBase(defaultServer)
            val fallbackAttempt = runCatching {
                doCreateOnServer(fallbackNormalized, nickname, maxMembers)
            }
            if (fallbackAttempt.isSuccess) {
                return@withLock Result.success(fallbackAttempt.getOrThrow())
            }
            val fallbackFailure = fallbackAttempt.exceptionOrNull() ?: failure
            _state.update { it.copy(error = fallbackFailure.displayMessage()) }
            return@withLock Result.failure(fallbackFailure)
        }

        _state.update { it.copy(error = failure.displayMessage()) }
        Result.failure(failure)
    }

    private suspend fun doCreateOnServer(serverBase: String, nickname: String, maxMembers: Int): String {
        val who = identityResolver.resolve(nickname) ?: throw PartyException("not_signed_in", "Sign in to listen together.")
        if (serverBase.isBlank()) throw PartyException("no_server", "Set the party server address first.")
        val membership = post(
            "$serverBase/api/parties",
            JoinRequest(
                who.userId, who.deviceId, who.name, who.avatar, maxMembers,
                autoplayEnabled = AppSettings.autoplay.value,
            ),
        )

        withContext(NonCancellable) {
            activePartyServerBase = serverBase
            token = membership.token
            prefs.edit()
                .putString(KEY_CODE, membership.code)
                .putString(KEY_TOKEN, membership.token)
                .apply()
            clock.reset()
            _state.value = State(
                code = membership.code,
                you = membership.you,
                members = membership.party.members,
                maxMembers = membership.party.maxMembers,
                playback = membership.party.playback,
                queue = membership.party.queue,
                connection = Connection.CONNECTING,
            )
        }
        connect()
        return membership.code
    }

    suspend fun joinParty(code: String, nickname: String = nickname()): Result<String> = switchMutex.withLock {
        val targetServer = resolveHttpBase(effectiveIdleServerBase())
        runCatching {
            doJoinOnServer(targetServer, code, nickname)
        }.onFailure { failure ->
            Log.w(TAG, "could not enter a party: ${redact(failure.message)}")
            _state.update { it.copy(error = failure.displayMessage()) }
        }
    }

    private suspend fun doJoinOnServer(serverBase: String, code: String, nickname: String): String {
        val who = identityResolver.resolve(nickname) ?: throw PartyException("not_signed_in", "Sign in to listen together.")
        if (serverBase.isBlank()) throw PartyException("no_server", "Set the party server address first.")
        val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != CODE_LENGTH) {
            throw PartyException("bad_code", "A party code is six letters or digits.")
        }
        refuseIfRecentlyKicked(cleaned)
        val membership = post(
            "$serverBase/api/parties/$cleaned/join",
            JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
        )

        withContext(NonCancellable) {
            activePartyServerBase = serverBase
            token = membership.token
            prefs.edit()
                .putString(KEY_CODE, membership.code)
                .putString(KEY_TOKEN, membership.token)
                .apply()
            clock.reset()
            _state.value = State(
                code = membership.code,
                you = membership.you,
                members = membership.party.members,
                maxMembers = membership.party.maxMembers,
                playback = membership.party.playback,
                queue = membership.party.queue,
                connection = Connection.CONNECTING,
            )
        }
        connect()
        return membership.code
    }

    suspend fun switchPartyWithRecovery(
        targetCustomServer: String,
        targetCode: String,
        nickname: String = nickname(),
    ): SwitchPartyResult = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val who = identityResolver.resolve(nickname)
                ?: return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), "Sign in to listen together.")
                } else {
                    SwitchPartyResult.TargetFailedNoParty("Sign in to listen together.")
                }

            val normTarget = when (val res = parseAndNormalizeServerUrl(targetCustomServer)) {
                is ServerUrlValidationResult.Valid -> res.normalizedUrl
                is ServerUrlValidationResult.Invalid -> return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), "Target server address is invalid.")
                } else {
                    SwitchPartyResult.TargetFailedNoParty("Target server address is invalid.")
                }
            }

            val targetBase = resolveHttpBase(normTarget)
            if (targetBase.isBlank()) {
                return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), "Target server address is invalid or missing.")
                } else {
                    SwitchPartyResult.TargetFailedNoParty("Target server address is invalid or missing.")
                }
            }

            val cleanedTargetCode = targetCode.filter { it.isLetterOrDigit() }.uppercase()
            if (cleanedTargetCode.length != CODE_LENGTH) {
                return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), "A party code is six letters or digits.")
                } else {
                    SwitchPartyResult.TargetFailedNoParty("A party code is six letters or digits.")
                }
            }

            if (kickLedger.contains(cleanedTargetCode)) {
                val refusal = "Couldn’t let you in — you’ve recently been kicked out of this party."
                return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), refusal)
                } else {
                    SwitchPartyResult.TargetFailedNoParty(refusal)
                }
            }

            val oldServerBase = activePartyServerBase ?: resolveHttpBase(_customServer.value)
            val oldCode = _state.value.code
            val oldToken = token

            val targetJoinResult = runCatching {
                post(
                    "$targetBase/api/parties/$cleanedTargetCode/join",
                    JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
                )
            }

            val membership = targetJoinResult.getOrElse { failure ->
                Log.w(TAG, "failed to join target party: ${redact(failure.message)}")
                val errorMsg = failure.displayMessage()
                return@withContext if (oldCode != null) {
                    SwitchPartyResult.TargetFailedRecovered(oldCode, errorMsg)
                } else {
                    SwitchPartyResult.TargetFailedNoParty(errorMsg)
                }
            }

            withContext(NonCancellable) {
                if (!oldCode.isNullOrBlank() && !oldToken.isNullOrBlank() &&
                    (oldServerBase != targetBase || !oldCode.equals(membership.code, ignoreCase = true))
                ) {
                    releaseStaleSlotOnServer(oldServerBase, oldCode, oldToken)
                }

                socketJob?.cancel()
                socketJob = null
                session = null

                activePartyServerBase = targetBase

                setCustomServerUrl(normTarget)

                token = membership.token
                prefs.edit()
                    .putString(KEY_CODE, membership.code)
                    .putString(KEY_TOKEN, membership.token)
                    .apply()
                clock.reset()
                _state.value = State(
                    code = membership.code,
                    you = membership.you,
                    members = membership.party.members,
                    maxMembers = membership.party.maxMembers,
                    playback = membership.party.playback,
                    queue = membership.party.queue,
                    connection = Connection.CONNECTING,
                )
            }

            connect()

            SwitchPartyResult.Success(membership.code)
        }
    }

    suspend fun leaveParty() = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val code = _state.value.code
            val held = token
            val currentServer = activePartyServerBase ?: resolveHttpBase(effectiveIdleServerBase())
            socketJob?.cancel()
            socketJob = null
            session = null
            clock.reset()
            token = null
            activePartyServerBase = null
            prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
            _state.value = State()
            if (code != null && held != null) {
                releaseStaleSlotOnServer(currentServer, code, held)
            }
        }
    }

    fun play(positionMs: Long? = null) = control("play") { positionMs?.let { put("positionMs", it) } }

    fun pause(positionMs: Long? = null) = control("pause") { positionMs?.let { put("positionMs", it) } }

    fun seek(positionMs: Long) = control("seek") { put("positionMs", positionMs) }

    fun next() = control("next") {}

    fun previous() = control("previous") {}

    fun setTrack(track: PartyTrack, positionMs: Long = 0, isPlaying: Boolean = true) =
        control("setTrack") {
            put("track", json.encodeToJsonElement(PartyTrack.serializer(), track))
            put("positionMs", positionMs)
            put("isPlaying", isPlaying)
        }

    fun setQueue(queue: List<PartyTrack>, index: Int) = control("setQueue") {
        put("queue", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PartyTrack.serializer()), queue))
        put("queueIndex", index)
    }

    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false) = control("queueAdd") {
        put("tracks", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PartyTrack.serializer()), tracks))
        put("playNext", playNext)
    }

    fun queueRemove(videoId: String) = control("queueRemove") {
        put("videoId", videoId)
    }

    fun queueClear() = control("queueClear") {}

    fun queueMove(fromIndex: Int, toIndex: Int, videoId: String? = null) = control("queueMove") {
        put("fromIndex", fromIndex)
        put("toIndex", toIndex)
        if (videoId != null) put("videoId", videoId)
    }

    fun setMaxMembers(value: Int) = control("setMaxMembers") { put("maxMembers", value) }

    fun kick(memberId: String) = control("kick") { put("memberId", memberId) }

    fun setAutoplay(enabled: Boolean) = control("setAutoplay") { put("enabled", enabled) }

    fun setHostOnlyControl(enabled: Boolean) =
        control("setHostOnlyControl") { put("enabled", enabled) }

    private fun control(action: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val frame = buildJsonObject {
            put("type", "control")
            put("action", action)
            body()
        }
        send(frame)
    }

    private fun send(frame: JsonObject) {
        val live = session ?: return
        scope.launch {
            runCatching { live.send(Frame.Text(frame.toString())) }
                .onFailure { Log.w(TAG, "control not sent: ${redact(it.message)}") }
        }
    }

    private val _awaitingStart = MutableStateFlow(false)
    val awaitingStart: StateFlow<Boolean> = _awaitingStart.asStateFlow()

    fun setAwaitingStart(value: Boolean) {
        _awaitingStart.value = value
    }

    fun partyPositionMs(): Long? {
        val playback = _state.value.playback
        val track = playback.track ?: return null
        if (!playback.isPlaying) return playback.positionMs
        val serverNow = clock.serverNowMs() ?: return playback.effectivePositionMs
        val elapsed = (serverNow - playback.anchorMs).coerceAtLeast(0)
        val position = playback.positionMs + elapsed
        val duration = track.durationMs
        return if (duration != null) minOf(position, duration) else position
    }

    fun msUntilStart(): Long {
        val playback = _state.value.playback
        if (!playback.isPlaying) return 0
        val serverNow = clock.serverNowMs() ?: return 0
        return (playback.anchorMs - serverNow).coerceAtLeast(0)
    }

    private fun connect() {
        socketJob?.cancel()
        socketJob = scope.launch { socketConnection.run() }
    }

    private fun recordKick(code: String) = kickLedger.record(code)

    private fun refuseIfRecentlyKicked(code: String) {
        if (kickLedger.contains(code)) {
            throw PartyException(
                "recently_kicked",
                "Couldn’t let you in — you’ve recently been kicked out of this party.",
            )
        }
    }

    fun myAvatarUrl(): String? = identityResolver.resolve()?.avatar

    private fun recordActivity(entry: PartyActivity) {
        val next = (listOf(entry) + _activity.value).take(100)
        _activity.value = next
    }

    suspend fun previewParty(code: String, server: String? = null): Result<PartyPreview> {
        val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != CODE_LENGTH) {
            return Result.failure(PartyException("bad_code", "A party code is six letters or digits."))
        }
        return runCatching {
            refuseIfRecentlyKicked(cleaned)
            val base = resolveHttpBase(server ?: effectiveIdleServerBase())
            if (base.isBlank()) throw PartyException("no_server", "Set the party server address first.")
            val response = http.get("$base/api/parties/$cleaned/preview")
            if (!response.status.isSuccess()) {
                val problem = response.toPartyException()
                if (problem.statusCode == 404 && problem.code == "http_404") {
                    return@runCatching PartyPreview(code = cleaned)
                }
                throw problem
            }
            response.body<PartyPreview>()
        }.onFailure {
            Log.w(TAG, "could not look up a party: ${redact(it.message)}")
        }
    }

    private suspend fun post(url: String, body: JoinRequest): PartyMembership {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) throw response.toPartyException()
        return response.body()
    }

    private suspend fun HttpResponse.toPartyException(): PartyException {
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return PartyException(
            code = parsed?.code.orEmpty().ifBlank { "http_${status.value}" },
            message = parsed?.message?.takeIf { it.isNotBlank() }
                ?: if (status.value == 422) "This account can't be used to jam."
                else "The party server said ${status.value}.",
            statusCode = status.value,
        )
    }

    fun normalizeServerBase(value: String): String =
        value.trim().trimEnd('/')

    private fun resolveHttpBase(server: String): String {
        val raw = normalizeServerBase(server).ifBlank { defaultServer }
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun redact(text: String?): String {
        var out = text.orEmpty()
        if (out.isEmpty()) return out
        listOfNotNull(defaultServer, _customServer.value, activePartyServerBase)
            .filter { it.isNotBlank() }
            .flatMap { listOf(it, it.substringAfter("://")) }
            .sortedByDescending(String::length)
            .forEach { out = out.replace(it, SERVER_PLACEHOLDER, ignoreCase = true) }
        return out.replace(ABSOLUTE_URL, SERVER_PLACEHOLDER)
    }

    private fun Throwable.displayMessage(): String = when (this) {
        is PartyException -> message ?: UNREACHABLE
        else -> UNREACHABLE
    }

    private fun wsBase(): String {
        val base = activePartyServerBase ?: effectiveIdleServerBase()
        return resolveHttpBase(base)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
    }

    const val CODE_LENGTH = 6

    private const val AVATAR_PX = 512
    private const val TAG = "ListenTogether"
    private const val PREFS = "nuvx_listen_together"
    private const val KEY_SERVER = "server_url"

    private const val SERVER_PLACEHOLDER = "<party server>"

    private const val UNREACHABLE = "Couldn’t reach the party server."

    private val ABSOLUTE_URL = Regex("""(?:https?|wss?)://[^\s,;)\]}'\"]+""", RegexOption.IGNORE_CASE)
    private const val KEY_CODE = "party_code"
    private const val KEY_TOKEN = "party_token"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_NICKNAME = "party_nickname"
    private const val KEY_KICKED = "party_kicked_until"

    private const val KICK_BLOCK_MS = 24L * 60 * 60 * 1000

    private const val HEALTH_POLL_INTERVAL_MS = 10_000L
    private const val HEALTH_MIN_GAP_MS = 5_000L
    private const val HEALTH_TIMEOUT_MS = 45_000L
}
