package online.automint.app.telemetry

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class TelemetryStore private constructor(private val appContext: Context) {

    private val mutex = Mutex()
    private val file = File(appContext.filesDir, QUEUE_FILE)
    private val tmpFile = File(appContext.filesDir, QUEUE_FILE + ".tmp")

    private val queue = ArrayList<Entry>()

    private var dirty = false

    private var flushing = false

    private var loaded = false

    private var droppedCount = 0L

    data class Entry(
        val id: String,
        val name: String,
        val ts: Long,
        val props: JSONObject,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("ts", ts)
            put("props", props)
        }
    }

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        loaded = true
        runCatching {
            if (!file.exists() && tmpFile.exists()) {
                tmpFile.renameTo(file)
            }
            if (!file.exists()) return@runCatching
            val text = file.readText()
            if (text.isBlank()) return@runCatching
            val root = JSONObject(text)
            droppedCount = root.optLong("dropped", 0L)
            val arr = root.optJSONArray("events") ?: JSONArray()
            for (i in 0 until arr.length()) {
                parseEntry(arr.optJSONObject(i))?.let { queue.add(it) }
            }
        }.onFailure {
            queue.clear()
            droppedCount = 0L
        }
    }

    suspend fun enqueue(name: String, props: JSONObject): Boolean = mutex.withLock {
        val before = queue.size
        addLocked(Entry(UUID.randomUUID().toString(), name, System.currentTimeMillis(), props))
        dirty = true
        val after = queue.size
        before < BATCH_TRIGGER && after >= BATCH_TRIGGER && !flushing
    }

    suspend fun enqueueDurable(name: String, props: JSONObject): Boolean = mutex.withLock {
        val before = queue.size
        addLocked(Entry(UUID.randomUUID().toString(), name, System.currentTimeMillis(), props))
        persistLocked()
        val after = queue.size
        before < BATCH_TRIGGER && after >= BATCH_TRIGGER && !flushing
    }

    suspend fun persistIfDirty() = mutex.withLock {
        if (dirty) persistLocked()
    }

    suspend fun snapshotBatch(): List<Entry> = mutex.withLock {
        ArrayList(queue)
    }

    suspend fun removeByIds(ids: Collection<String>) = mutex.withLock {
        if (ids.isEmpty()) return@withLock
        val set = ids.toHashSet()
        val removed = queue.removeAll { it.id in set }
        if (removed) {
            dirty = true
            persistLocked()
        }
    }

    suspend fun purge() = mutex.withLock {
        queue.clear()
        droppedCount = 0L
        dirty = true
        persistLocked()
    }

    suspend fun size(): Int = mutex.withLock { queue.size }

    suspend fun beginFlush(): Boolean = mutex.withLock {
        if (flushing) return@withLock false
        flushing = true
        true
    }

    suspend fun endFlush() = mutex.withLock {
        flushing = false
    }

    private fun addLocked(entry: Entry) {
        queue.add(entry)
        if (queue.size <= QUEUE_CAP) return
        evictOneLocked()
    }

    private fun evictOneLocked() {
        var dropIndex = -1
        for (i in queue.indices) {
            if (queue[i].name != EVENT_SESSION_START) {
                dropIndex = i
                break
            }
        }
        if (dropIndex < 0) dropIndex = 0
        queue.removeAt(dropIndex)
        droppedCount++
        dirty = true
    }

    private fun persistLocked() {
        runCatching {
            val arr = JSONArray()
            for (e in queue) arr.put(e.toJson())
            val root = JSONObject().apply {
                put("schema", SCHEMA)
                put("dropped", droppedCount)
                put("events", arr)
            }
            FileOutputStream(tmpFile).use { fos ->
                fos.write(root.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(file)) {
                file.delete()
                tmpFile.renameTo(file)
            }
            dirty = false
        }.onFailure {}
    }

    private fun parseEntry(obj: JSONObject?): Entry? {
        if (obj == null) return null
        val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null
        if (!obj.has("ts")) return null
        val ts = obj.optLong("ts", -1L)
        if (ts < 0L) return null
        val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val props = obj.optJSONObject("props") ?: JSONObject()
        return Entry(id, name, ts, props)
    }

    companion object {
        const val SCHEMA = 1
        const val QUEUE_CAP = 500
        const val BATCH_TRIGGER = 20

        const val QUEUE_FILE = "telemetry-queue.json"

        const val EVENT_SESSION_START = "app.session_start"
        const val META_DROPPED = "telemetry.dropped_count"

        @Volatile
        private var instance: TelemetryStore? = null

        fun getInstance(context: Context): TelemetryStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: TelemetryStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
