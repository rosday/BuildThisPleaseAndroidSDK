package io.buildthisplease.compose

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test

class TranslationCacheTest {
    private val originals = listOf(FeedbackTranslationField("title", "Private original title"), FeedbackTranslationField("body", "Same body"))
    private val translated = listOf(FeedbackTranslationField("title", "Translated title"), originals[1])

    @Test fun `disk cache restores translations without persisting unchanged original fields`() = runTest {
        val directory = Files.createTempDirectory("translation-cache").toFile()
        try {
            val file = directory.resolve("cache.json")
            val key = translationHash("model", "en", *originals.map { it.text }.toTypedArray())
            TranslationCache(file).put(key, originals, translated)
            assertEquals(translated, TranslationCache(file).get(key, originals))
            assertFalse(file.readText().contains("Private original title"))
            assertFalse(file.readText().contains("Same body"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `no-op entries avoid persisting any original text`() = runTest {
        val directory = Files.createTempDirectory("translation-no-op").toFile()
        try {
            val file = directory.resolve("cache.json")
            TranslationCache(file).put(translationHash("noop"), originals, originals)
            assertEquals(originals, TranslationCache(file).get(translationHash("noop"), originals))
            assertFalse(file.readText().contains("Same body"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `cache expires at thirty days and bounds its entry count`() = runTest {
        var time = 1L
        val cache = TranslationCache(null) { time }
        repeat(501) { index ->
            time++
            cache.put(translationHash(index.toString()), originals, translated)
        }
        assertNull(cache.get(translationHash("0"), originals))
        assertNotNull(cache.get(translationHash("500"), originals))
        time += TranslationCache.LIFETIME_MS
        assertNull(cache.get(translationHash("500"), originals))
    }

    @Test fun `disk footprint is bounded and corrupt files are ignored`() = runTest {
        val directory = Files.createTempDirectory("translation-bounds").toFile()
        try {
            val file = directory.resolve("cache.json")
            val cache = TranslationCache(file)
            val large = listOf(FeedbackTranslationField("title", "t".repeat(1_200_000)), originals[1])
            cache.put(translationHash("first"), originals, large)
            cache.put(translationHash("second"), originals, large)
            assertTrue(file.length() <= TranslationCache.MAXIMUM_BYTES)
            assertNull(cache.get(translationHash("first"), originals))
            file.writeText("broken json")
            assertNull(TranslationCache(file).get(translationHash("second"), originals))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `hash includes unambiguous utf8 boundaries`() {
        assertNotEquals(translationHash("ab", "c"), translationHash("a", "bc"))
        assertNotEquals(translationHash("en", "hola"), translationHash("es", "hola"))
        assertNotEquals(translationHash("zh-Hans"), translationHash("zh-Hant"))
        assertEquals(translationHash("你好", "🙂"), translationHash("你好", "🙂"))
    }

    @Test fun `well-formed json with invalid cached fields is ignored`() = runTest {
        val directory = Files.createTempDirectory("translation-invalid-fields").toFile()
        try {
            val file = directory.resolve("cache.json")
            val key = translationHash("invalid")
            listOf("\"title\":\"\"", "\"unknown\":\"text\"").forEach { fields ->
                file.writeText("{\"$key\":{\"createdAt\":1,\"fields\":{$fields}}}")
                assertNull(TranslationCache(file) { 2L }.get(key, originals))
            }
        } finally { directory.deleteRecursively() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `interrupted initial load retries and preserves older disk entries`() = runTest {
        val directory = Files.createTempDirectory("translation-interrupted-load").toFile()
        try {
            val file = directory.resolve("cache.json")
            val oldKey = translationHash("old-entry")
            TranslationCache(file).put(oldKey, originals, translated)
            val cache = TranslationCache(file, ioDispatcher = StandardTestDispatcher(testScheduler))
            val interrupted = launch(UnconfinedTestDispatcher(testScheduler)) { cache.get(oldKey, originals) }
            interrupted.cancelAndJoin()
            assertEquals(translated, cache.get(oldKey, originals))
            cache.put(translationHash("new-entry"), originals, translated)
            assertEquals(translated, TranslationCache(file).get(oldKey, originals))
        } finally { directory.deleteRecursively() }
    }
}
