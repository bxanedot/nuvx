package com.licht.nuvx.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local Wi-Fi Sync: the same Nuvx Sync log, served by nuvx on a computer on this network (`app/desktop/src/lan_sync`).
 * Pairing agrees a key with P-256 ECDH (the phone commits to its key first, so a man in the middle cannot make both
 * screens show the same code) and the user approves on the computer after comparing a 6-digit code. Every request
 * after that is sealed with AES-256-GCM, bound to this device, timestamped and carrying a one-time nonce.
 * Must stay byte-compatible with `lan_sync/crypto.rs`.
 */
object LanSyncCrypto {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val KEY_INFO = "nuvx-lan-v1 key".toByteArray()
    private val SAS_INFO = "nuvx-lan-v1 sas".toByteArray()

    fun b64(bytes: ByteArray): String = b64.encodeToString(bytes)
    fun unb64(text: String): ByteArray? = runCatching { unb64.decode(text.trim()) }.getOrNull()
    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** Uncompressed SEC1: 0x04 ‖ x ‖ y, 65 bytes, as `p256::EncodedPoint`. */
    fun encode(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    private fun fixed(n: BigInteger): ByteArray = n.toByteArray().let { b ->
        when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** The device key and the 6-digit code both screens show; null when [hostPublic] is not a P-256 point. */
    fun agree(mine: KeyPair, hostPublic: ByteArray): Pair<ByteArray, String>? = agree(mine, hostPublic, mineIsDevice = true)

    /** The host's side of [agree] (a phone hosting other phones): same key and code, salt still phone first. */
    fun agreeAsHost(host: KeyPair, devicePublic: ByteArray): Pair<ByteArray, String>? = agree(host, devicePublic, mineIsDevice = false)

    private fun agree(mine: KeyPair, theirPublic: ByteArray, mineIsDevice: Boolean): Pair<ByteArray, String>? = runCatching {
        require(theirPublic.size == 65 && theirPublic[0] == 4.toByte())
        val params = (mine.public as ECPublicKey).params
        val point = ECPoint(BigInteger(1, theirPublic.copyOfRange(1, 33)), BigInteger(1, theirPublic.copyOfRange(33, 65)))
        val theirs = java.security.KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(mine.private); doPhase(theirs, true); generateSecret() }
        val minePublic = encode(mine.public as ECPublicKey)
        val salt = if (mineIsDevice) minePublic + theirPublic else theirPublic + minePublic
        val prk = hmac(salt, shared)
        val key = hmac(prk, KEY_INFO + 1).copyOf(32)
        val sas = hmac(prk, SAS_INFO + 1).copyOf(4)
        val number = ((sas[0].toLong() and 0xff) shl 24) or ((sas[1].toLong() and 0xff) shl 16) or ((sas[2].toLong() and 0xff) shl 8) or (sas[3].toLong() and 0xff)
        key to "%06d".format(number % 1_000_000)
    }.getOrNull()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    private operator fun ByteArray.plus(b: Int) = this + byteArrayOf(b.toByte())

    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): String {
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
        return b64(nonce + cipher.doFinal(plain))
    }

    fun open(key: ByteArray, aad: ByteArray, sealed: String): ByteArray? = runCatching {
        val raw = unb64(sealed)!!
        require(raw.size >= 28)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
            updateAAD(aad)
            doFinal(raw, 12, raw.size - 12)
        }
    }.getOrNull()
}

/** A computer address the user typed: `host` or `host:port`. Only this network is ever contacted. */
data class LanAddress(val host: String, val port: Int, val bluetooth: Boolean = false) {
    override fun toString() = when {
        bluetooth -> "bt/$host"
        ':' in host -> "[$host]:$port"
        else -> "$host:$port"
    }

    companion object {
        const val DEFAULT_PORT = 47731

        fun parse(text: String): LanAddress? {
            // A computer reached over Bluetooth: "bt/AA:BB:CC:DD:EE:FF".
            text.trim().removePrefix("bt/").takeIf { text.trim().startsWith("bt/") }?.uppercase()?.let { mac ->
                return if (LanBluetooth.isAddress(mac)) LanAddress(mac, 0, bluetooth = true) else null
            }
            val t = text.trim().removePrefix("http://").trimEnd('/')
            if (t.isEmpty() || t.any { it.isWhitespace() || it == '/' || it == '@' }) return null
            val (host, port) = when {
                t.startsWith("[") -> t.substringAfter('[').substringBefore(']') to t.substringAfter("]:", "").ifEmpty { null }
                t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':')
                else -> t to null
            }
            val p = port?.toIntOrNull() ?: if (port == null) DEFAULT_PORT else return null
            if (host.isEmpty() || p !in 1..65535) return null
            return LanAddress(host, p)
        }

        /** Private, link-local or loopback addresses only: never something on the internet. */
        fun isLocal(address: InetAddress): Boolean =
            address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
                (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)
    }
}

/** Minimal HTTP/1.1 over a socket to the computer, so no cleartext permission is needed for the rest of the app. */
internal object LanHttp {
    private const val MAX_REPLY = 4 * 1024 * 1024

    fun exchange(
        to: LanAddress,
        method: String,
        path: String,
        body: String?,
        device: String? = null,
        connectMs: Int = 5_000,
        readMs: Int = 20_000,
    ): Pair<Int, String> {
        if (to.bluetooth) {
            // Kept open between requests: a Bluetooth connection takes a second or two to set up.
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nhost: nuvx\r\ncontent-type: application/json\r\n")
                device?.let { append("x-nuvx-device: $it\r\n") }
                append("content-length: ${bytes.size}\r\n\r\n")
            }
            return LanBluetooth.exchange(to.host, head.toByteArray() + bytes, ::read)
        }
        val ip = InetAddress.getAllByName(to.host).firstOrNull(LanAddress::isLocal)
            ?: throw IOException("Not an address on this network")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, to.port), connectMs)
            socket.soTimeout = readMs
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nhost: $to\r\nconnection: close\r\ncontent-type: application/json\r\n")
                device?.let { append("x-nuvx-device: $it\r\n") }
                append("content-length: ${bytes.size}\r\n\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
            return read(socket.getInputStream())
        }
    }

    private fun read(input: InputStream): Pair<Int, String> {
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) out.write(c)
                if (out.size() > 8192) throw IOException("Header too long")
            }
            return out.toString(Charsets.UTF_8.name())
        }
        val status = line().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Not a nuvx computer")
        var length = -1
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            if (h.substringBefore(':').trim().equals("content-length", true)) length = h.substringAfter(':').trim().toIntOrNull() ?: -1
        }
        if (length < 0 || length > MAX_REPLY) throw IOException("Bad reply length")
        val body = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = input.read(body, at, length - at)
            if (n < 0) throw IOException("Reply cut short")
            at += n
        }
        return status to body.decodeToString()
    }
}

/**
 * Lets the unchanged [SyncClient] and [SyncEngine] talk to a paired computer: each request is sealed, sent over
 * [LanHttp] and the sealed reply opened, so OkHttp itself never opens a connection.
 */
class LanSyncInterceptor(private val to: LanAddress, private val deviceId: String, private val key: ByteArray) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val text = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
        val body: JsonElement = text?.takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement) ?: JsonNull
        val nonce = LanSyncCrypto.b64(LanSyncCrypto.randomBytes(16))
        val target = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        val plain = buildJsonObject {
            put("m", request.method); put("p", target); put("t", System.currentTimeMillis()); put("n", nonce); put("b", body)
        }.toString()
        val sealed = LanSyncCrypto.seal(key, deviceId.toByteArray(), plain.toByteArray())
        val (status, reply) = LanHttp.exchange(to, "POST", "/lan/v1", sealed, deviceId)
        val (code, json) = if (status == 200) {
            val opened = LanSyncCrypto.open(key, "resp:$deviceId".toByteArray(), reply)?.let { Json.parseToJsonElement(it.decodeToString()).jsonObject }
                ?: throw IOException("Unreadable reply")
            if (opened["n"]?.jsonPrimitive?.contentOrNull != nonce) throw IOException("Reply for another request")
            (opened["s"]?.jsonPrimitive?.intOrNull ?: 500) to (opened["b"] ?: JsonNull).toString()
        } else {
            status to reply
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }
}

/** A pairing in progress: show [code] and wait for the computer named [hostName] to approve it. */
class LanPairing(val to: LanAddress, val pairingId: String, val code: String, val hostName: String, internal val key: ByteArray)

object LanPairingClient {
    /** Asks the computer at [to] to pair. Blocking. */
    fun start(to: LanAddress, deviceName: String): Result<LanPairing> = runCatching {
        val mine = LanSyncCrypto.newKeyPair()
        val public = LanSyncCrypto.encode(mine.public as ECPublicKey)
        val (s, started) = LanHttp.exchange(to, "POST", "/lan/pair/start", buildJsonObject {
            put("deviceName", deviceName); put("commit", LanSyncCrypto.sha256Hex(public))
        }.toString())
        val json = Json.parseToJsonElement(started).jsonObject
        if (s != 201) throw IOException(json.message() ?: "HTTP $s")
        val id = json.str("pairingId")
        val hostKey = LanSyncCrypto.unb64(json.str("hostKey")) ?: throw IOException("Bad host key")
        val (key, code) = LanSyncCrypto.agree(mine, hostKey) ?: throw IOException("Bad host key")
        val (r, revealed) = LanHttp.exchange(to, "POST", "/lan/pair/reveal", buildJsonObject {
            put("pairingId", id); put("deviceKey", LanSyncCrypto.b64(public))
        }.toString())
        if (r != 200) throw IOException(Json.parseToJsonElement(revealed).jsonObject.message() ?: "HTTP $r")
        LanPairing(to, id, code, json["hostName"]?.jsonPrimitive?.contentOrNull ?: to.host, key)
    }

    /** [SyncResult.Waiting] until the computer approves, then [SyncResult.Joined] once (its token is the device key). */
    fun claim(p: LanPairing): SyncResult = try {
        val proof = LanSyncCrypto.seal(p.key, "nuvx-pair-claim:${p.pairingId}".toByteArray(), "claim".toByteArray())
        val (s, reply) = LanHttp.exchange(p.to, "POST", "/lan/pair/claim", buildJsonObject {
            put("pairingId", p.pairingId)
            put("proof", proof)
        }.toString())
        val json = runCatching { Json.parseToJsonElement(reply).jsonObject }.getOrNull()
        when (s) {
            200 -> SyncResult.Joined(json!!.str("spaceId"), json.str("deviceId"), LanSyncCrypto.b64(p.key))
            202 -> SyncResult.Waiting
            else -> SyncResult.Stop(json?.message() ?: "HTTP $s")
        }
    } catch (e: IOException) {
        SyncResult.RetryLater("Network: ${e.javaClass.simpleName}")
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.message() = this["message"]?.jsonPrimitive?.contentOrNull
}

/**
 * Finds the nuvx computer on this phone's Wi-Fi that shows [code] (Settings → Nuvx Sync / Nuvx Connect on the
 * computer), so nobody has to type an address. The code only locates it: pairing still needs the approval on the
 * computer and the matching six-digit code.
 */
object LanFinder {
    val CODE = Regex("[0-9]{4}")

    // shortcut: searches the phone's own /24 only; wider home networks still pair by typing the address.
    suspend fun find(phoneIpv4: java.net.Inet4Address, code: String): LanAddress? = find(phoneIpv4) { it.text("code") == code }

    /** The computer showing [code], among the computers this phone is paired with over Bluetooth. */
    suspend fun findOverBluetooth(code: String): LanAddress? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        LanBluetooth.pairedComputers().firstNotNullOfOrNull { (mac, _) ->
            LanAddress(mac, 0, bluetooth = true).takeIf { hello(it)?.text("code") == code }
        }
    }

    /** Finds the computer this phone paired with again after its address changed (same [hostId]). */
    suspend fun findHost(phoneIpv4: java.net.Inet4Address, hostId: String): LanAddress? = find(phoneIpv4) { it.text("id") == hostId }

    /** The computer's `/lan/hello`, or null when nothing (or not nuvx) answers there. Blocking. */
    fun hello(to: LanAddress): JsonObject? = runCatching {
        val (status, reply) = LanHttp.exchange(to, "GET", "/lan/hello", null, connectMs = 700, readMs = if (to.bluetooth) 8_000 else 1_500)
        Json.parseToJsonElement(reply).jsonObject.takeIf { status == 200 && it.text("app") == "nuvx" }
    }.onFailure { if (to.bluetooth) com.licht.nuvx.data.DebugLog.w("LanSync", "Bluetooth hello failed: $it") }.getOrNull()

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private suspend fun find(phoneIpv4: java.net.Inet4Address, accept: (JsonObject) -> Boolean): LanAddress? = kotlinx.coroutines.coroutineScope {
        val base = phoneIpv4.address
        val gate = kotlinx.coroutines.sync.Semaphore(48)
        val found = kotlinx.coroutines.CompletableDeferred<LanAddress?>()
        val probes = (1..254).filter { it != (base[3].toInt() and 0xff) }.map { last ->
            launch(kotlinx.coroutines.Dispatchers.IO) {
                gate.acquire()
                try {
                    val to = LanAddress("${base[0].toInt() and 0xff}.${base[1].toInt() and 0xff}.${base[2].toInt() and 0xff}.$last", LanAddress.DEFAULT_PORT)
                    if (!found.isCompleted && hello(to)?.let(accept) == true) found.complete(to)
                } finally {
                    gate.release()
                }
            }
        }
        launch { probes.joinAll(); found.complete(null) }
        found.await().also { probes.forEach { it.cancel() } }
    }

    /** This phone's private IPv4 address on its current network, if any. */
    fun phoneAddress(context: android.content.Context): java.net.Inet4Address? {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        // Only a home network: never scan a mobile carrier's network (its 10.x addresses look private too).
        val network = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        } ?: return null
        val links = cm.getLinkProperties(network) ?: return null
        return links.linkAddresses.map { it.address }.filterIsInstance<java.net.Inet4Address>().firstOrNull { it.isSiteLocalAddress }
    }
}
