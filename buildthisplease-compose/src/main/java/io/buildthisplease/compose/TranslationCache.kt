package io.buildthisplease.compose

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal fun translationHash(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Only changed fields are persisted. No-op entries retain a hash, not another copy of originals. */
internal class TranslationCache(
    private val file: File?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val fields: Map<String, String>, val createdAt: Long)
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private var loaded = false

    suspend fun get(key: String, originals: List<FeedbackTranslationField>): List<FeedbackTranslationField>? = mutex.withLock {
        load()
        prune()
        entries[key]?.let { entry ->
            if (!originals.map { it.id }.containsAll(entry.fields.keys) || entry.fields.values.any(String::isBlank)) {
                entries.remove(key)
                null
            } else originals.map { it.copy(text = entry.fields[it.id] ?: it.text) }
        }
    }

    suspend fun put(key: String, originals: List<FeedbackTranslationField>, translated: List<FeedbackTranslationField>) = mutex.withLock {
        load()
        entries[key] = Entry(translated.zip(originals).filter { (result, original) -> result.text != original.text }
            .associate { (result, _) -> result.id to result.text }, now())
        prune()
        save()
    }

    private suspend fun load() {
        if (loaded) return
        val stored = withContext(ioDispatcher) {
            runCatching {
                file?.takeIf { it.isFile && it.length() <= MAXIMUM_BYTES }?.readText()?.let { data ->
                    Json.parseToJsonElement(data).jsonObject.mapNotNull { (key, value) ->
                        if (!key.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
                        val item = value.jsonObject
                        val date = item["createdAt"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                        val fields = item["fields"]?.jsonObject?.mapValues { (_, text) ->
                            text.jsonPrimitive.contentOrNull ?: error("Invalid cached field")
                        } ?: return@mapNotNull null
                        key to Entry(fields, date)
                    }?.toMap()
                }
            }.getOrNull().orEmpty()
        }
        entries.putAll(stored)
        prune()
        loaded = true
    }

    private fun prune() {
        val time = now()
        entries.entries.removeAll { (_, value) -> value.createdAt > time || time - value.createdAt >= LIFETIME_MS }
        entries.entries.sortedByDescending { it.value.createdAt }.drop(MAXIMUM_ENTRIES).forEach { entries.remove(it.key) }
    }

    private suspend fun save() {
        val destination = file ?: return
        var data = encode()
        while (data.toByteArray(Charsets.UTF_8).size > MAXIMUM_BYTES && entries.isNotEmpty()) {
            entries.remove(entries.minBy { it.value.createdAt }.key)
            data = encode()
        }
        withContext(ioDispatcher) {
            // Persistence is best effort; a full disk must not prevent showing an in-memory result.
            runCatching {
                val parent = requireNotNull(destination.parentFile)
                parent.mkdirs()
                val temporary = File.createTempFile("translations-", ".tmp", parent)
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(data.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    check(temporary.renameTo(destination))
                } finally { temporary.delete() }
            }
        }
    }

    private fun encode(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        entries.forEach { (key, entry) -> put(key, buildJsonObject {
            put("createdAt", entry.createdAt)
            put("fields", buildJsonObject { entry.fields.forEach { (id, text) -> put(id, text) } })
        }) }
    })

    companion object {
        const val LIFETIME_MS = 30L * 24 * 60 * 60 * 1_000
        const val MAXIMUM_ENTRIES = 500
        const val MAXIMUM_BYTES = 2 * 1024 * 1024
    }
}
