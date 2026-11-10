package com.bxane.nuvx.data.sources.addon

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bxane.nuvx.BuildConfig
import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AddonAuth {
    const val TOKEN_HEADER = "X-nuvx-Token"
    const val PROOF_HEADER = "X-nuvx-Proof"
    private const val PROOF_CONTEXT = "nuvx-addon-proof/v1"

    private const val TAG = "nuvx"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "nuvx_addon_auth_"

    private const val REFRESH_BEFORE_MS = 5 * 60_000L

    private const val PROOF_REUSE_MS = 30_000L

    private const val REFUSED_BACKOFF_MS = 15 * 60_000L

    private const val TRANSIENT_BACKOFF_MS = 30_000L

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mutex = Mutex()

    private class Session(val token: String, val expiresAtMs: Long, val alias: String) {
        val tokenHash: String = b64url(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }

    private class Refusal(val message: String, val untilMs: Long, val transient: Boolean) {
        fun raise(): Nothing = throw if (transient) AddonUnavailable(message) else AddonException(message)
    }

    @Volatile private var prefs: SharedPreferences? = null

    @Volatile private var testerPrefs: SharedPreferences? = null
    @Volatile private var session: Session? = null
    @Volatile private var refusal: Refusal? = null
    @Volatile private var clockOffsetMs = 0L

    @Volatile
    internal var testHeaders: (suspend (host: String, rejectedToken: String?) -> Map<String, String>)? = null

    private val proofs = ConcurrentHashMap<String, Pair<Long, String>>()

    fun init(context: Context) {
        testerPrefs = context.getSharedPreferences("nuvx_tester", Context.MODE_PRIVATE)
        val p = context.getSharedPreferences("nuvx_addon_auth", Context.MODE_PRIVATE)
        prefs = p
        val token = p.getString("token", null)
        val alias = p.getString("alias", null)
        val expiresAt = p.getLong("expiresAt", 0L)
        clockOffsetMs = p.getLong("clockOffset", 0L)
        if (token != null && alias != null && expiresAt > System.currentTimeMillis()) {
            session = Session(token, expiresAt, alias)
        }
    }

    suspend fun headersFor(host: String, rejectedToken: String? = null): Map<String, String> {
        val normalized = normalizeHost(host)
        testHeaders?.let { return it(normalized, rejectedToken) }
        repeat(2) {
            val current = session(rejectedToken)
            try {
                return mapOf(TOKEN_HEADER to current.token, PROOF_HEADER to proof(current, normalized))
            } catch (e: KeyGone) {
                TrackLog.w(TAG, "addon auth: hardware key missing, attesting again")
                drop(current)
            }
        }
        throw AddonException("nuvx couldn't use this device's secure key store")
    }

    fun standingRefusal(): String? = refusal?.takeIf { it.untilMs > System.currentTimeMillis() }?.message

    internal fun refuseForTest(message: String?, transient: Boolean = false) {
        refusal = message?.let { Refusal(it, System.currentTimeMillis() + REFUSED_BACKOFF_MS, transient) }
    }

    fun hasTesterCode(): Boolean = !testerPrefs?.getString(TESTER_KEY, null).isNullOrEmpty()

    fun setTesterCode(raw: String?) {
        val code = normalizeTesterCode(raw)
        testerPrefs?.edit()?.apply { if (code.isEmpty()) remove(TESTER_KEY) else putString(TESTER_KEY, code) }?.apply()
        refusal = null
        session?.let(::drop)
        TrackLog.i(TAG, "addon auth: tester code ${if (code.isEmpty()) "removed" else "saved"}")
    }

    internal fun normalizeTesterCode(raw: String?): String =
        raw.orEmpty().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    private const val TESTER_KEY = "code"

    fun rejectionMessage(code: String?): String = when (code) {
        "stale_proof" -> "This addon rejected the request because this phone's clock is wrong"
        "unavailable" -> "This addon couldn't check nuvx's verification right now"
        else -> "This addon didn't accept nuvx's verification" + (code?.let { " ($it)" } ?: "")
    }

    private fun usable(s: Session?, rejectedToken: String?): Session? =
        s?.takeIf { it.token != rejectedToken && it.expiresAtMs - REFRESH_BEFORE_MS > System.currentTimeMillis() }

    private suspend fun session(rejectedToken: String?): Session {
        usable(session, rejectedToken)?.let { return it }
        return mutex.withLock {
            usable(session, rejectedToken)?.let { return@withLock it }
            refusal?.takeIf { it.untilMs > System.currentTimeMillis() }?.raise()
            val fresh = try {
                attest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttestRefused) {
                TrackLog.w(TAG, "addon auth: ${e.message}")
                Refusal(e.message ?: "Verification refused", System.currentTimeMillis() + e.backoffMs, transient = false)
                    .also { refusal = it }.raise()
            } catch (e: Exception) {
                val message = "Couldn't reach nuvx's addon verification service"
                TrackLog.w(TAG, "addon auth: $message", e)
                Refusal(message, System.currentTimeMillis() + TRANSIENT_BACKOFF_MS, transient = true)
                    .also { refusal = it }.raise()
            }
            refusal = null
            session?.let { old -> if (old.alias != fresh.alias) deleteKey(old.alias) }
            session = fresh
            proofs.clear()
            prefs?.edit()
                ?.putString("token", fresh.token)
                ?.putString("alias", fresh.alias)
                ?.putLong("expiresAt", fresh.expiresAtMs)
                ?.putLong("clockOffset", clockOffsetMs)
                ?.apply()
            fresh
        }
    }

    private fun drop(s: Session) {
        if (session === s) {
            session = null
            prefs?.edit()?.clear()?.apply()
        }
        deleteKey(s.alias)
    }

    private class AttestRefused(message: String, val backoffMs: Long = REFUSED_BACKOFF_MS) : Exception(message)

    private class KeyGone : Exception()

    @Serializable private data class ChallengeResponse(val challenge: String, val serverTime: Long)

    @Serializable private data class AttestRequest(
        val challenge: String,
        val chain: List<String>,
        val testerCode: String? = null,
    )

    @Serializable private data class AttestResponse(val token: String, val expiresAt: Long, val serverTime: Long? = null)

    @Serializable private data class ServiceError(val error: String = "", val message: String = "")

    private suspend fun attest(): Session = withContext(Dispatchers.IO) {
        val base = BuildConfig.ADDON_AUTH_URL
        val started = System.currentTimeMillis()
        val challenge = json.decodeFromString<ChallengeResponse>(call(Request.Builder().url("$base/v1/challenge").build()))
        clockOffsetMs = challenge.serverTime - (started + System.currentTimeMillis()) / 2

        val alias = ALIAS_PREFIX + UUID.randomUUID()
        val chain = try {
            generateAttestedKey(alias, Base64.getUrlDecoder().decode(challenge.challenge))
        } catch (e: Exception) {
            deleteKey(alias)
            throw AttestRefused("This device can't prove it is running the official nuvx app (no hardware key attestation)")
        }
        try {
            val body = json.encodeToString(
                AttestRequest.serializer(),
                AttestRequest(
                    challenge.challenge,
                    chain.map { Base64.getEncoder().encodeToString(it) },
                    testerPrefs?.getString(TESTER_KEY, null)?.ifEmpty { null },
                ),
            )
            val request = Request.Builder().url("$base/v1/attest")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val answer = json.decodeFromString<AttestResponse>(call(request))
            TrackLog.i(TAG, "addon auth: device verified, token valid until ${answer.expiresAt}")
            Session(answer.token, answer.expiresAt, alias)
        } catch (e: Exception) {
            deleteKey(alias)
            throw e
        }
    }

    private fun call(request: Request): String =
        client.newCall(request.newBuilder().header("Accept", "application/json").build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> text
                response.code == 429 || response.code >= 500 -> throw java.io.IOException("auth service HTTP ${response.code}")
                else -> {
                    val error = runCatching { json.decodeFromString<ServiceError>(text) }.getOrNull()
                    val backoff = if (error?.error == "outdated_app") TRANSIENT_BACKOFF_MS else REFUSED_BACKOFF_MS
                    throw AttestRefused(
                        "nuvx couldn't verify this device: " + (error?.message?.ifBlank { null } ?: "HTTP ${response.code}"),
                        backoff,
                    )
                }
            }
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun generateAttestedKey(alias: String, challenge: ByteArray): List<ByteArray> {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        val chain = keyStore().getCertificateChain(alias)
        require(!chain.isNullOrEmpty() && chain.size >= 2) { "no attestation chain" }
        return chain.map { it.encoded }
    }

    private fun deleteKey(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun proof(s: Session, host: String): String {
        val cacheKey = "$host|${s.tokenHash}"
        val now = System.currentTimeMillis()
        proofs[cacheKey]?.let { (madeAt, header) -> if (now - madeAt < PROOF_REUSE_MS) return header }

        val privateKey = runCatching { keyStore().getKey(s.alias, null) as? PrivateKey }.getOrNull() ?: throw KeyGone()
        val timestamp = now + clockOffsetMs
        val message = proofMessage(host, timestamp, s.tokenHash)
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message.toByteArray())
            sign()
        }
        val header = "v1.$timestamp.${b64url(derToRaw(der))}"
        if (proofs.size > 64) proofs.clear()
        proofs[cacheKey] = now to header
        return header
    }

    internal fun proofMessage(host: String, timestampMs: Long, tokenHash: String) =
        "$PROOF_CONTEXT\n$host\n$timestampMs\n$tokenHash"

    internal fun normalizeHost(host: String): String =
        host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')

    internal fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        fun byte(): Int = der[i++].toInt() and 0xff
        fun length(): Int {
            val first = byte()
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7f) { n = (n shl 8) or byte() }
            return n
        }
        fun integer(): ByteArray {
            require(byte() == 0x02) { "expected INTEGER" }
            val len = length()
            val value = der.copyOfRange(i, i + len)
            i += len
            val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
            require(trimmed.size <= 32) { "integer too large for P-256" }
            return ByteArray(32 - trimmed.size) + trimmed
        }
        require(byte() == 0x30) { "expected SEQUENCE" }
        length()
        return integer() + integer()
    }

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    }
}
