package online.automint.app.telemetry

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import online.automint.app.BuildConfig
import online.automint.app.consent.ConsentManager
import online.automint.app.identity.DeviceIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TelemetryClient private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = TelemetryStore.getInstance(appContext)
    private val consentManager = ConsentManager(appContext)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionMutex = Mutex()

    private val consentEnabled = AtomicBoolean(false)

    private val consentChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ConsentManager.KEY_TELEMETRY_CONSENT ||
                key == ConsentManager.KEY_TELEMETRY_CHOICE_MADE
            ) {
                consentEnabled.set(consentManager.telemetryAllowed())
            }
        }

    @Volatile private var initialized = false

    @Volatile private var sessionId: String? = null

    @Volatile private var lastSessionId: String? = null

    @Volatile private var sessionStartElapsed: Long = 0L

    @Volatile private var sessionStartWallClock: Long = 0L

    suspend fun ensureInitialized() {
        if (initialized) return
        sessionMutex.withLock {
            if (initialized) return@withLock
            consentEnabled.set(consentManager.telemetryAllowed())
            prefs.registerOnSharedPreferenceChangeListener(consentChangeListener)
            store.load()
            runInitStateMachine()
            initialized = true
        }
    }

    private suspend fun runInitStateMachine() {
        lastSessionId = prefs.getString(KEY_LAST_SESSION_ID, null)
        val openSessionId = prefs.getString(KEY_OPEN_SESSION_ID, null)
        if (openSessionId != null) {
            lastSessionId = openSessionId
            val startWall = prefs.getLong(KEY_SESSION_START_WALL, 0L)
            val lastFg = prefs.getLong(KEY_LAST_FOREGROUND_AT, startWall)
            val reason = classifyEndReason()
            val durationMs = (lastFg - startWall).coerceAtLeast(0L)
            if (emitSessionEnd(openSessionId, startWall, durationMs, reason)) {
                TelemetryFlushWorker.enqueue(appContext)
            }
            clearOpenSession()
        }
    }

    private fun classifyEndReason(): String {
        val crashed = prefs.getBoolean(KEY_CRASH_FLAG, false)
        return if (crashed) REASON_CRASHED else REASON_TERMINATED
    }

    private suspend fun startSession() {
        val id = UUID.randomUUID().toString()
        val nowWall = System.currentTimeMillis()
        sessionId = id
        lastSessionId = id
        sessionStartElapsed = SystemClock.elapsedRealtime()
        sessionStartWallClock = nowWall
        prefs.edit(commit = true) {
            putString(KEY_OPEN_SESSION_ID, id)
            putString(KEY_LAST_SESSION_ID, id)
            putLong(KEY_SESSION_START_WALL, nowWall)
            putLong(KEY_LAST_FOREGROUND_AT, nowWall)
            putBoolean(KEY_CRASH_FLAG, false)
            putBoolean(KEY_CLEAN_MARKER, false)
        }
        if (consentEnabled.get()) {
            val props = JSONObject().apply { put("startedAt", nowWall) }
            val shouldFlush = store.enqueueDurable(EVENT_SESSION_START, props)
            if (shouldFlush) TelemetryFlushWorker.enqueue(appContext)
        }
    }

    private suspend fun emitSessionEnd(
        id: String,
        startedAt: Long,
        durationMs: Long,
        reason: String,
    ): Boolean {
        if (!consentEnabled.get()) return false
        val props = JSONObject().apply {
            put("startedAt", startedAt)
            put("durationMs", durationMs)
            put("reason", reason)
            put("sessionId", id)
        }
        return store.enqueueDurable(EVENT_SESSION_END, props)
    }

    fun onForeground() {
        scope.launch {
            ensureInitialized()
            sessionMutex.withLock {
                val nowWall = System.currentTimeMillis()
                when {
                    sessionId == null -> {
                        startSession()
                    }
                    nowWall - prefs.getLong(KEY_LAST_FOREGROUND_AT, nowWall) > IDLE_TIMEOUT_MS -> {
                        val cur = sessionId!!
                        val durationMs = SystemClock.elapsedRealtime() - sessionStartElapsed
                        if (emitSessionEnd(cur, sessionStartWallClock, durationMs, REASON_IDLE_TIMEOUT)) {
                            TelemetryFlushWorker.enqueue(appContext)
                        }
                        clearOpenSession()
                        startSession()
                    }
                    else -> {
                        prefs.edit(commit = true) { putLong(KEY_LAST_FOREGROUND_AT, nowWall) }
                    }
                }
            }
        }
    }

    fun onBackground() {
        scope.launch {
            ensureInitialized()
            val nowWall = System.currentTimeMillis()
            prefs.edit(commit = true) {
                putLong(KEY_LAST_FOREGROUND_AT, nowWall)
                putBoolean(KEY_CLEAN_MARKER, true)
            }
            store.persistIfDirty()
            TelemetryFlushWorker.enqueue(appContext)
            flush()
        }
    }

    private fun clearOpenSession() {
        prefs.edit(commit = true) {
            remove(KEY_OPEN_SESSION_ID)
        }
        sessionId = null
    }

    fun installCrashHandler(previous: Thread.UncaughtExceptionHandler?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                prefs.edit(commit = true) { putBoolean(KEY_CRASH_FLAG, true) }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun track(name: String, props: JSONObject = JSONObject()) {
        if (!consentEnabled.get()) return
        scope.launch {
            ensureInitialized()
            if (!consentEnabled.get()) return@launch
            val shouldFlush = if (isHighValue(name)) {
                store.enqueueDurable(name, props)
            } else {
                store.enqueue(name, props)
            }
            if (shouldFlush) TelemetryFlushWorker.enqueue(appContext)
        }
    }

    fun setConsent(enabled: Boolean) {
        consentEnabled.set(enabled)
        consentManager.setTelemetryConsent(enabled)
        scope.launch {
            if (!enabled) {
                store.purge()
                TelemetryFlushWorker.cancel(appContext)
            }
        }
    }

    private fun isHighValue(name: String): Boolean =
        name == EVENT_SESSION_START ||
            name == EVENT_SESSION_END ||
            name == EVENT_CONTENT_LOAD_FAILED

    suspend fun flush(): FlushResult {
        ensureInitialized()
        if (!consentEnabled.get()) return FlushResult.SUCCESS
        if (!store.beginFlush()) return FlushResult.SUCCESS
        try {
            val batch = store.snapshotBatch()
            if (batch.isEmpty()) return FlushResult.SUCCESS
            if (!consentEnabled.get()) return FlushResult.SUCCESS
            val payload = buildPayload(batch)
            val result = post(payload)
            return when (result) {
                PostOutcome.SUCCESS -> {
                    store.removeByIds(batch.map { it.id })
                    FlushResult.SUCCESS
                }
                PostOutcome.PERMANENT -> {
                    store.removeByIds(batch.map { it.id })
                    FlushResult.SUCCESS
                }
                PostOutcome.TRANSIENT -> FlushResult.RETRY
                PostOutcome.ABORTED -> FlushResult.SUCCESS
            }
        } finally {
            store.endFlush()
        }
    }

    private fun buildPayload(batch: List<TelemetryStore.Entry>): JSONObject {
        val identity = DeviceIdentity.get(appContext)
        val events = JSONArray()
        for (e in batch) {
            events.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("ts", e.ts)
                put("props", e.props)
            })
        }
        val app = JSONObject().apply {
            put("platform", "android")
            put("version", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
        }
        val session = JSONObject().apply {
            put("sessionId", sessionId ?: lastSessionId ?: "unknown")
            put("startedAt", sessionStartWallClock)
        }
        return JSONObject().apply {
            put("schema", TelemetryStore.SCHEMA)
            put("deviceId", identity.deviceId)
            put("installId", identity.installId)
            put("sentAt", System.currentTimeMillis())
            put("app", app)
            put("env", JSONObject(identity.envJson()))
            put("session", session)
            put("events", events)
        }
    }

    private fun post(payload: JSONObject): PostOutcome {
        val url = BuildConfig.TARGET_URL.trimEnd('/') + ENDPOINT_PATH
        val parsed = runCatching { URL(url) }.getOrNull() ?: return PostOutcome.PERMANENT
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            return PostOutcome.PERMANENT
        }
        if (!consentEnabled.get()) return PostOutcome.ABORTED

        var conn: HttpURLConnection? = null
        return try {
            conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            if (!consentEnabled.get()) return PostOutcome.ABORTED
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            when {
                code in 200..299 -> PostOutcome.SUCCESS
                code == 408 || code == 429 -> PostOutcome.TRANSIENT
                code in 300..399 -> PostOutcome.TRANSIENT
                code in 400..499 -> {
                    if (looksLikeTelemetryResponse(conn)) PostOutcome.PERMANENT
                    else PostOutcome.TRANSIENT
                }
                else -> PostOutcome.TRANSIENT
            }
        } catch (_: Exception) {
            PostOutcome.TRANSIENT
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun looksLikeTelemetryResponse(conn: HttpURLConnection): Boolean {
        val contentType = conn.contentType?.lowercase().orEmpty()
        if (contentType.contains("application/json")) return true
        val body = runCatching {
            (conn.errorStream ?: conn.inputStream)
                ?.bufferedReader()
                ?.use(BufferedReader::readText)
                .orEmpty()
        }.getOrDefault("")
        if (body.isEmpty()) return false
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return false
        return runCatching { JSONObject(trimmed); true }.getOrDefault(false)
    }

    private enum class PostOutcome { SUCCESS, PERMANENT, TRANSIENT, ABORTED }

    enum class FlushResult { SUCCESS, RETRY }

    companion object {
        const val ENDPOINT_PATH = "/api/telemetry"

        const val IDLE_TIMEOUT_MS = 30L * 60L * 1000L

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000

        const val EVENT_SESSION_START = "app.session_start"
        const val EVENT_SESSION_END = "app.session_end"
        const val EVENT_CONTENT_LOAD_FAILED = "content.load_failed"

        const val REASON_CRASHED = "crashed"
        const val REASON_TERMINATED = "terminated"
        const val REASON_IDLE_TIMEOUT = "idle_timeout"

        const val KEY_OPEN_SESSION_ID = "telemetry_open_session_id"
        const val KEY_LAST_SESSION_ID = "telemetry_last_session_id"
        const val KEY_SESSION_START_WALL = "telemetry_session_start_wall"
        const val KEY_LAST_FOREGROUND_AT = "telemetry_last_foreground_at"
        const val KEY_CRASH_FLAG = "telemetry_crash_flag"
        const val KEY_CLEAN_MARKER = "telemetry_clean_marker"

        @Volatile
        private var instance: TelemetryClient? = null

        fun getInstance(context: Context): TelemetryClient {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: TelemetryClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
