package com.ownapps.app.uihider

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Persistent key/value cache for UIHider scripts. Reads come from an in-memory map (so scripts can
 * cache expensive results and read them back instantly on later runs); writes update memory
 * immediately and are flushed to a JSON file on a short debounce so disk I/O never blocks a run.
 */
class ScriptStore(private val file: File) {

    private val gson = Gson()
    private val lock = Any()
    private val data: HashMap<String, HashMap<String, Any?>> = load()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    private fun load(): HashMap<String, HashMap<String, Any?>> {
        if (!file.exists()) return HashMap()
        return try {
            val type = object : TypeToken<HashMap<String, HashMap<String, Any?>>>() {}.type
            gson.fromJson(file.readText(), type) ?: HashMap()
        } catch (_: Exception) {
            HashMap()
        }
    }

    fun get(scriptId: String, key: String): Any? = synchronized(lock) { data[scriptId]?.get(key) }

    fun has(scriptId: String, key: String): Boolean =
        synchronized(lock) { data[scriptId]?.containsKey(key) == true }

    fun put(scriptId: String, key: String, value: Any?) {
        synchronized(lock) { data.getOrPut(scriptId) { HashMap() }[key] = value }
        scheduleFlush()
    }

    fun remove(scriptId: String, key: String) {
        synchronized(lock) { data[scriptId]?.remove(key) }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(1000)
            flushNow()
        }
    }

    fun flushNow() {
        val json = synchronized(lock) { gson.toJson(data) }
        try {
            file.parentFile?.mkdirs()
            file.writeText(json)
        } catch (_: Exception) {}
    }

    /** Flush any pending writes and stop the background scope. Call when the service is destroyed. */
    fun close() {
        flushJob?.cancel()
        flushNow()
        scope.cancel()
    }
}
