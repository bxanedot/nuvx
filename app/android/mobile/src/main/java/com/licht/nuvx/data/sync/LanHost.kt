package com.licht.nuvx.data.sync

import android.content.Context
import android.util.AtomicFile
import com.licht.nuvx.auth.EncryptedPrefs
import com.licht.nuvx.data.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * This phone as a LAN Sync host for other phones: the same protocol as the desktop app (`lan_sync/host.rs`), so a
 * phone pairs with another phone exactly as it pairs with a computer: approval here, the same 6-digit code on both
 * screens, AES-GCM envelopes bound to the device, replay protection, revocation. Off until turned on.
 *
 * This phone takes part in its own space as one more device over loopback, so its sync, speaker and file copy use
 * the same client code as everywhere else.
 */
object LanHost {
    data class Pending(val id: String, val name: String, val code: String)
    data class Device(val id: String, val name: String, val createdMs: Long, val seenMs: Long)
    data class State(
        val running: Boolean = false,
        val port: Int = 0,
        val code: String = "",
        val pending: List<Pending> = emptyList(),
        val devices: List<Device> = emptyList(),
        val error: String? = null,
    )

    /** What this phone plays, for Nuvx Connect; set by the playback service. */
    interface Speaker {
        fun state(): JsonObject
        /** Runs a validated command on this phone's player; false when it is not one this phone can do. */
        fun apply(command: JsonObject): Boolean
    }

    /** This phone's downloads, for copying to and from paired phones; set by the download code. */
    interface Files {
        fun list(): JsonArray
        fun read(id: String, offset: Long, max: Int): Pair<ByteArray, Long>?
        fun has(id: String): Boolean
        fun receive(id: String, file: File, title: String, artist: String, art: String?, durationMs: Long?, ext: String)
    }

    @Volatile var speaker: Speaker? = null
    @Volatile var files: Files? = null

    /** Another phone pushed new ops: this phone's own library pulls them now instead of at its next periodic sync. */
    @Volatile var onRemoteOps: (() -> Unit)? = null

    private const val API_VERSION = 1
    private const val PAIRING_TTL_MS = 5 * 60_000L
    private const val MAX_PENDING = 4
    private const val MAX_DEVICES = 20
    private const val MAX_BODY = 512 * 1024
    private const val MAX_OPS_PER_PUSH = 200
    private const val MAX_OP_BYTES = 32 * 1024
    private const val MAX_OPS = 200_000
    private const val PULL_LIMIT = 500
    private const val CLOCK_SKEW_MS = 2 * 60_000L
    private const val MAX_CONNECTIONS = 8
    private const val CHUNK = 1024 * 1024
    private const val UPLOAD_CHUNK = 192 * 1024
    private const val MAX_UPLOAD = 500L * 1024 * 1024

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** Other phones may play through this one (Nuvx Connect). Off until turned on, like on the computer. */
    private val _allowRemote = MutableStateFlow(false)
    val allowRemote = _allowRemote.asStateFlow()

    fun setAllowRemote(on: Boolean) {
        _allowRemote.value = on
        app.getSharedPreferences("nuvx_lan_host", Context.MODE_PRIVATE).edit().putBoolean("allow_remote", on).apply()
    }

    private lateinit var app: Context
    private val keysStore by lazy { EncryptedPrefs.open(app, "nuvx_lan_host_keys", "nuvx_lan_host_keys_plain") }
    private val file by lazy { AtomicFile(File(app.filesDir, "lan_host.json")) }
    private val lock = Any()

    private var hostId = ""
    private var spaceId = ""
    private val devices = mutableListOf<Device>()
    private val ops = mutableListOf<JsonObject>()
    private val opIds = HashSet<String>()
    private val keys = HashMap<String, ByteArray>()
    private val nonces = HashMap<String, Long>()
    private var findCode = ""
    private var server: ServerSocket? = null

    private sealed interface Pairing {
        val name: String
        val expires: Long
        data class Committed(override val name: String, override val expires: Long, val commit: String, val key: KeyPair) : Pairing
        data class Waiting(override val name: String, override val expires: Long, val deviceKey: ByteArray, val code: String) : Pairing
        data class Approved(override val name: String, override val expires: Long, val deviceId: String) : Pairing
        data class Denied(override val name: String, override val expires: Long) : Pairing
    }
    private val pairings = LinkedHashMap<String, Pairing>()

    fun init(context: Context) {
        app = context.applicationContext
        _allowRemote.value = app.getSharedPreferences("nuvx_lan_host", Context.MODE_PRIVATE).getBoolean("allow_remote", false)
        load()
    }

    private val hostName: String get() = NuvxSync.deviceName

    private fun load() = synchronized(lock) {
        val saved = runCatching { Json.parseToJsonElement(file.readFully().decodeToString()).jsonObject }.getOrNull()
        hostId = saved?.str("hostId") ?: hex(LanSyncCrypto.randomBytes(12))
        spaceId = saved?.str("spaceId") ?: hex(LanSyncCrypto.randomBytes(12))
        devices.clear()
        saved?.get("devices")?.jsonArray?.forEach { d ->
            val o = d.jsonObject
            devices += Device(o.str("id") ?: return@forEach, o.str("name").orEmpty(), o.long("createdMs"), o.long("seenMs"))
        }
        ops.clear()
        saved?.get("ops")?.jsonArray?.forEach { ops += it.jsonObject }
        opIds.clear()
        ops.mapNotNullTo(opIds) { it.str("id") }
        keys.clear()
        devices.forEach { d -> keysStore.getString("k_${d.id}", null)?.let(LanSyncCrypto::unb64)?.let { keys[d.id] = it } }
        if (saved == null) persist()
    }

    private fun persist() {
        val json = buildJsonObject {
            put("hostId", hostId)
            put("spaceId", spaceId)
            put("devices", buildJsonArray {
                devices.forEach { d ->
                    add(buildJsonObject { put("id", d.id); put("name", d.name); put("createdMs", d.createdMs); put("seenMs", d.seenMs) })
                }
            })
            put("ops", JsonArray(ops))
        }.toString().toByteArray()
        val out = file.startWrite()
        try {
            out.write(json)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            DebugLog.w(TAG, "could not save the host state: $e")
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------

    /** Starts listening on this phone's Wi-Fi (local peers only) and joins its own space over loopback. */
    fun start(): Boolean = synchronized(lock) {
        if (server != null) return true
        val socket = (0 until 10).firstNotNullOfOrNull { i ->
            runCatching { ServerSocket(LanAddress.DEFAULT_PORT + i, 8) }.getOrNull()
        } ?: run {
            publish(error = "No free port")
            return false
        }
        server = socket
        findCode = "%04d".format(java.security.SecureRandom().nextInt(10_000))
        val busy = AtomicInteger(0)
        thread(name = "lan-host", isDaemon = true) {
            while (!socket.isClosed) {
                val peer = runCatching { socket.accept() }.getOrNull() ?: continue
                if (!LanAddress.isLocal(peer.inetAddress) || busy.get() >= MAX_CONNECTIONS) {
                    runCatching { peer.close() }
                    continue
                }
                busy.incrementAndGet()
                thread(name = "lan-host-conn", isDaemon = true) {
                    try {
                        peer.use(::serve)
                    } catch (e: IOException) {
                        DebugLog.d(TAG, "connection: $e")
                    } finally {
                        busy.decrementAndGet()
                    }
                }
            }
        }
        publish()
        DebugLog.i(TAG, "hosting on port ${socket.localPort}")
        true
    }

    fun stop() = synchronized(lock) {
        runCatching { server?.close() }
        server = null
        pairings.clear()
        publish()
    }

    /**
     * Adds this phone to its own space as a device (no pairing needed: it is its own host) and returns how it
     * reaches itself: loopback address, device id and key.
     */
    fun selfJoin(): Triple<LanAddress, String, ByteArray>? = synchronized(lock) {
        val port = server?.localPort ?: return null
        val id = "self-$hostId"
        val key = keys[id] ?: LanSyncCrypto.randomBytes(32).also { k ->
            keys[id] = k
            keysStore.edit().putString("k_$id", LanSyncCrypto.b64(k)).apply()
            devices.removeAll { it.id == id }
            devices += Device(id, hostName, System.currentTimeMillis(), System.currentTimeMillis())
            persist()
        }
        Triple(LanAddress("127.0.0.1", port), id, key)
    }

    val spaceIdForSelf: String get() = spaceId

    private fun publish(error: String? = null) {
        val now = System.currentTimeMillis()
        _state.value = State(
            running = server != null,
            port = server?.localPort ?: 0,
            code = findCode,
            pending = pairings.mapNotNull { (id, p) -> (p as? Pairing.Waiting)?.takeIf { it.expires > now }?.let { Pending(id, it.name, it.code) } },
            devices = devices.filterNot { it.id.startsWith("self-") },
            error = error,
        )
    }

    fun approve(pairingId: String): Boolean = synchronized(lock) {
        val p = pairings[pairingId] as? Pairing.Waiting ?: return false
        // A phone pairing again (its app data was cleared) replaces its old entry instead of leaving a dead twin.
        devices.filter { it.name == p.name && !it.id.startsWith("self-") }.forEach { revoke(it.id) }
        if (devices.size >= MAX_DEVICES) return false
        val id = hex(LanSyncCrypto.randomBytes(12))
        keys[id] = p.deviceKey
        keysStore.edit().putString("k_$id", LanSyncCrypto.b64(p.deviceKey)).apply()
        devices += Device(id, p.name, System.currentTimeMillis(), System.currentTimeMillis())
        pairings[pairingId] = Pairing.Approved(p.name, p.expires, id)
        persist()
        publish()
        true
    }

    fun deny(pairingId: String) = synchronized(lock) {
        pairings[pairingId]?.let { pairings[pairingId] = Pairing.Denied(it.name, it.expires) }
        publish()
    }

    fun revoke(deviceId: String): Boolean = synchronized(lock) {
        if (deviceId.startsWith("self-")) return false
        val removed = devices.removeAll { it.id == deviceId }
        keys.remove(deviceId)
        keysStore.edit().remove("k_$deviceId").apply()
        if (removed) persist()
        publish()
        removed
    }

    // ---- HTTP over the socket ---------------------------------------------------------------------------

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = line(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        var length = 0
        var device: String? = null
        for (i in 0 until 64) {
            val h = line(input) ?: break
            if (h.isEmpty()) break
            when (h.substringBefore(':').trim().lowercase()) {
                "content-length" -> length = h.substringAfter(':').trim().toIntOrNull() ?: Int.MAX_VALUE
                "x-nuvx-device" -> device = h.substringAfter(':').trim()
            }
        }
        val (status, body) = if (length > MAX_BODY || length < 0) {
            413 to error("request_too_large", "Request body is too large.")
        } else {
            val bytes = ByteArray(length)
            var at = 0
            while (at < length) {
                val n = input.read(bytes, at, length - at)
                if (n < 0) throw IOException("Request cut short")
                at += n
            }
            handle(method, path, device, bytes)
        }
        val out = socket.getOutputStream()
        val payload = body.toByteArray()
        out.write("HTTP/1.1 $status X\r\ncontent-type: application/json\r\ncache-control: no-store\r\ncontent-length: ${payload.size}\r\nconnection: close\r\n\r\n".toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (c == '\n'.code) break
            if (c != '\r'.code) out.write(c)
            if (out.size() > 8192) throw IOException("Header too long")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // ---- protocol ------------------------------------------------------------------------------------------

    internal fun handle(method: String, path: String, deviceHeader: String?, body: ByteArray): Pair<Int, String> = synchronized(lock) {
        expire()
        val json = runCatching { Json.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull()
        when {
            method == "GET" && path == "/lan/hello" -> 200 to buildJsonObject {
                put("app", "nuvx"); put("api", API_VERSION); put("name", hostName); put("id", hostId); put("code", findCode); put("bt", JsonNull)
            }.toString()
            method == "POST" && path == "/lan/pair/start" -> {
                val name = json?.str("deviceName")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }
                val commit = json?.str("commit")?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' } }
                when {
                    name == null || commit == null -> 422 to error("invalid_pairing", "deviceName and commit are required.")
                    pairings.size >= MAX_PENDING -> 429 to error("busy", "Too many pairing requests at once. Try again in a few minutes.")
                    else -> {
                        val id = hex(LanSyncCrypto.randomBytes(16))
                        val key = LanSyncCrypto.newKeyPair()
                        pairings[id] = Pairing.Committed(name, System.currentTimeMillis() + PAIRING_TTL_MS, commit.lowercase(), key)
                        201 to buildJsonObject {
                            put("pairingId", id)
                            put("hostKey", LanSyncCrypto.b64(LanSyncCrypto.encode(key.public as java.security.interfaces.ECPublicKey)))
                            put("hostId", hostId)
                            put("hostName", hostName)
                        }.toString()
                    }
                }
            }
            method == "POST" && path == "/lan/pair/reveal" -> {
                val id = json?.str("pairingId").orEmpty()
                val devicePub = json?.str("deviceKey")?.let(LanSyncCrypto::unb64)
                val p = pairings[id]
                when {
                    p == null -> 404 to error("no_such_pairing", "That pairing request is unknown or has expired.")
                    p !is Pairing.Committed -> 409 to error("already_revealed", "This pairing already has a key.")
                    devicePub == null || LanSyncCrypto.sha256Hex(devicePub) != p.commit -> {
                        pairings.remove(id) // a wrong reveal ends the attempt: no second guess at the commitment
                        401 to error("bad_commit", "The key does not match what this device committed to.")
                    }
                    else -> when (val agreed = LanSyncCrypto.agreeAsHost(p.key, devicePub)) {
                        null -> {
                            pairings.remove(id)
                            422 to error("bad_key", "Not a valid key.")
                        }
                        else -> {
                            pairings[id] = Pairing.Waiting(p.name, p.expires, agreed.first, agreed.second)
                            publish()
                            200 to """{"ok":true}"""
                        }
                    }
                }
            }
            method == "POST" && path == "/lan/pair/claim" -> {
                val id = json?.str("pairingId").orEmpty()
                when (val p = pairings[id]) {
                    null -> 404 to error("no_such_pairing", "That pairing request is unknown or has expired.")
                    is Pairing.Approved -> {
                        pairings.remove(id) // single use
                        200 to buildJsonObject { put("deviceId", p.deviceId); put("spaceId", spaceId); put("hostId", hostId); put("hostName", hostName) }.toString()
                    }
                    is Pairing.Denied -> {
                        pairings.remove(id)
                        403 to error("denied", "This phone declined the request.")
                    }
                    else -> 202 to """{"pending":true}"""
                }
            }
            method == "POST" && path == "/lan/v1" -> sealed(deviceHeader.orEmpty(), body)
            else -> 404 to error("not_found", "Not found.")
        }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        if (pairings.values.removeAll { it.expires < now }) publish()
        nonces.values.removeAll { it < now }
    }

    private fun sealed(deviceId: String, body: ByteArray): Pair<Int, String> {
        val unauthorized = 401 to error("unauthorized", "This device is not paired with this phone.")
        val key = keys[deviceId] ?: return unauthorized
        val plain = LanSyncCrypto.open(key, deviceId.toByteArray(), body.decodeToString()) ?: return unauthorized
        val req = runCatching { Json.parseToJsonElement(plain.decodeToString()).jsonObject }.getOrNull() ?: return unauthorized
        val nonce = req.str("n").orEmpty()
        val at = req["t"]?.jsonPrimitive?.longOrNull ?: 0L
        val now = System.currentTimeMillis()
        // Replay protection: fresh timestamps only, and each nonce once within the window.
        if (nonce.length < 16 || kotlin.math.abs(at - now) > CLOCK_SKEW_MS || nonces.containsKey(nonce)) return unauthorized
        if (nonces.size > 10_000) nonces.clear()
        nonces[nonce] = now + 2 * CLOCK_SKEW_MS
        devices.indexOfFirst { it.id == deviceId }.takeIf { it >= 0 && devices[it].seenMs < now - 60_000 }?.let { devices[it] = devices[it].copy(seenMs = now) }
        val target = req.str("p").orEmpty()
        val (path, query) = target.substringBefore('?') to target.substringAfter('?', "")
        val (status, reply) = route(deviceId, req.str("m").orEmpty(), path, query, req["b"] ?: JsonNull)
        val envelope = buildJsonObject { put("s", status); put("n", nonce); put("b", reply) }.toString()
        return 200 to LanSyncCrypto.seal(key, "resp:$deviceId".toByteArray(), envelope.toByteArray())
    }

    private fun route(device: String, method: String, path: String, query: String, body: JsonElement): Pair<Int, JsonElement> {
        fun err(status: Int, code: String, message: String) = status to buildJsonObject { put("error", code); put("message", message) }
        val obj = body as? JsonObject
        return when {
            method == "POST" && path == "/v1/ops" -> {
                val list = obj?.get("ops") as? JsonArray
                if (list == null || list.isEmpty() || list.size > MAX_OPS_PER_PUSH) return err(422, "invalid_ops", "ops must hold 1 to 200 operations.")
                val rows = list.map { raw ->
                    val o = raw as? JsonObject ?: return err(422, "invalid_op", "Each op needs id, kind, entity and a payload under 32 KB.")
                    fun field(k: String, max: Int) = o.str(k)?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }
                    val id = field("id", 64)
                    val kind = field("kind", 64)
                    val entity = field("entity", 200)
                    val payload = o["payload"] ?: JsonNull
                    if (id == null || kind == null || entity == null || payload.toString().length > MAX_OP_BYTES) {
                        return err(422, "invalid_op", "Each op needs id, kind, entity and a payload under 32 KB.")
                    }
                    listOf(id, kind, entity) to (payload to (o["clientMs"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()))
                }
                if (ops.size + rows.size > MAX_OPS) return err(413, "space_full", "This sync log is full.")
                val before = ops.size
                val seqs = rows.map { (ids, rest) ->
                    val (id, kind, entity) = ids
                    (if (id in opIds) ops.firstOrNull { it.str("id") == id }?.get("seq")?.jsonPrimitive?.longOrNull else null) ?: run {
                        val seq = (ops.lastOrNull()?.get("seq")?.jsonPrimitive?.longOrNull ?: 0L) + 1
                        ops += buildJsonObject {
                            put("seq", seq); put("id", id); put("deviceId", device); put("kind", kind); put("entity", entity)
                            put("payload", rest.first); put("clientMs", rest.second)
                        }
                        opIds += id
                        seq
                    }
                }
                persist()
                if (ops.size > before && !device.startsWith("self-")) onRemoteOps?.invoke()
                200 to buildJsonObject { put("seqs", JsonArray(seqs.map(::JsonPrimitive))) }
            }
            method == "GET" && path == "/v1/ops" -> {
                val after = query.split('&').firstNotNullOfOrNull { it.removePrefix("after=").takeIf { v -> v != it }?.toLongOrNull() } ?: 0L
                val page = ops.filter { (it["seq"]?.jsonPrimitive?.longOrNull ?: 0) > after }.take(PULL_LIMIT)
                200 to buildJsonObject { put("api", API_VERSION); put("ops", JsonArray(page)); put("more", page.size == PULL_LIMIT) }
            }
            method == "GET" && path == "/v1/devices" -> 200 to buildJsonObject {
                put("devices", buildJsonArray {
                    devices.forEach { d ->
                        add(buildJsonObject { put("id", d.id); put("name", d.name); put("createdMs", d.createdMs); put("seenMs", d.seenMs) })
                    }
                })
                put("you", device)
            }
            method == "DELETE" && path == "/v1/space" -> {
                // A phone leaving with "delete server data" only removes itself: this phone's playlists stay.
                revoke(device)
                200 to buildJsonObject { put("ok", true) }
            }
            method == "DELETE" && path.startsWith("/v1/devices/") -> {
                val target = path.removePrefix("/v1/devices/")
                if (target.startsWith("self-")) err(409, "host", "Remove the other phone from this one instead.")
                else if (revoke(target)) 200 to buildJsonObject { put("ok", true) }
                else err(404, "no_such_device", "That device is not paired.")
            }
            method == "GET" && path == "/v1/files" -> 200 to buildJsonObject { put("files", files?.list() ?: JsonArray(emptyList())) }
            method == "GET" && path.startsWith("/v1/files/") -> {
                val id = path.removePrefix("/v1/files/")
                val offset = query.split('&').firstNotNullOfOrNull { it.removePrefix("offset=").takeIf { v -> v != it }?.toLongOrNull() } ?: 0L
                val piece = if (VIDEO_ID.matches(id)) files?.read(id, offset, CHUNK) else null
                if (piece == null) err(404, "not_found", "That download is not here.")
                else 200 to buildJsonObject { put("data", LanSyncCrypto.b64(piece.first)); put("offset", offset); put("size", piece.second) }
            }
            method == "POST" && path.startsWith("/v1/files/") -> receive(device, path.removePrefix("/v1/files/"), obj)
            (method == "GET" || method == "POST") && path == "/v1/connect" && speaker == null ->
                err(403, "connect_off", "This phone is not taking remote control right now.")
            method == "GET" && path == "/v1/connect" -> 200 to (speaker?.state() ?: buildJsonObject { put("track", JsonNull); put("playing", false) })
            method == "POST" && path == "/v1/connect" -> {
                if (obj == null || speaker?.apply(obj) != true) err(422, "bad_command", "Unknown or invalid command.")
                else 202 to buildJsonObject { put("queued", true) }
            }
            else -> err(404, "not_found", "Not found.")
        }
    }

    private class Upload(val file: File, val size: Long, var written: Long, val meta: JsonObject)
    private val uploads = HashMap<String, Upload>()

    private fun receive(device: String, id: String, body: JsonObject?): Pair<Int, JsonElement> {
        fun err(status: Int, code: String) = status to buildJsonObject { put("error", code); put("message", "That piece was refused; send the song again.") }
        val target = files ?: return err(404, "not_found")
        if (!VIDEO_ID.matches(id) || body == null) return err(404, "not_found")
        val key = "$device/$id"
        val offset = body["offset"]?.jsonPrimitive?.longOrNull ?: return err(422, "bad_piece")
        val size = body["size"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..MAX_UPLOAD } ?: return err(413, "too_large")
        val data = body.str("data")?.let(LanSyncCrypto::unb64)?.takeIf { it.isNotEmpty() && it.size <= UPLOAD_CHUNK } ?: return err(422, "bad_piece")
        if (offset == 0L) {
            if (target.has(id)) {
                uploads.remove(key)
                return 200 to buildJsonObject { put("done", true); put("already", true) }
            }
            if (!uploads.containsKey(key) && uploads.size >= 4) return err(429, "busy")
            val tmp = File(app.cacheDir, "lan-in-${hex(device.toByteArray()).take(16)}-$id.part").apply { writeBytes(ByteArray(0)) }
            uploads[key] = Upload(tmp, size, 0, body)
        }
        val up = uploads[key] ?: return err(409, "start_over")
        if (offset != up.written || size != up.size || up.written + data.size > up.size) return err(409, "start_over")
        up.file.appendBytes(data)
        up.written += data.size
        if (up.written < up.size) return 200 to buildJsonObject { put("received", up.written) }
        uploads.remove(key)
        val head = up.file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        val ext = if (head.contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) "webm" else "m4a"
        try {
            target.receive(
                id, up.file,
                up.meta.str("title")?.take(300).orEmpty(), up.meta.str("artist")?.take(300).orEmpty(),
                up.meta.str("art")?.takeIf { it.startsWith("https://") && it.length <= 300 },
                up.meta["durationMs"]?.jsonPrimitive?.longOrNull, ext,
            )
        } catch (e: Exception) {
            return err(500, "unwritable")
        } finally {
            up.file.delete()
        }
        return 200 to buildJsonObject { put("done", true) }
    }

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private const val TAG = "LanHost"

    private fun error(code: String, message: String) = buildJsonObject { put("error", code); put("message", message) }.toString()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

    /** This phone's address on its Wi-Fi, for the pairing screen. */
    fun wifiAddress(): String? = LanFinder.phoneAddress(app)?.hostAddress?.let { "$it:${_state.value.port}" }

}
