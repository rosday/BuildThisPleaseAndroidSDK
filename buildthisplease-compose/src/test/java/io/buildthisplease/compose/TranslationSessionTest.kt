package io.buildthisplease.compose

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSessionTest {
    private val fields = listOf(FeedbackTranslationField("title", "Hola"))
    private fun request(text: String = "Hola", target: String = "en", model: String = "nano-v3") =
        FeedbackTranslationRequest(listOf(FeedbackTranslationField("title", text)), target, model)

    @Test fun `unavailable model never starts translation`() = runTest {
        val provider = FakeProvider().apply { ready = null }
        val session = TranslationSession(provider, TranslationCache(null))
        val resumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.whileResumed() }
        assertNull(session.model.value)
        assertNull(session.translate(request()))
        assertEquals(0, provider.calls)
        resumed.cancelAndJoin()
    }

    @Test fun `shared cache deduplicates concurrent screens and separates edited text locale and model`() = runTest {
        val provider = FakeProvider()
        val cache = TranslationCache(null)
        val list = TranslationSession(provider, cache)
        val detail = TranslationSession(provider, cache)
        val resumed = listOf(list, detail).map { session ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.whileResumed() }
        }
        val a = async { list.translate(request()) }
        val b = async { detail.translate(request()) }
        assertEquals(a.await(), b.await())
        assertEquals(1, provider.calls)
        list.translate(request(text = "Edited"))
        list.translate(request(target = "fr"))
        assertEquals(3, provider.calls)
        provider.ready = "nano-v4"
        try { list.translate(request()); fail("Expected eligibility change") }
        catch (failure: FeedbackTranslationException) { assertEquals(FeedbackTranslationException.Reason.UNAVAILABLE, failure.reason) }
        list.translate(request(model = "nano-v4"))
        assertEquals(4, provider.calls)
        resumed.forEach { it.cancelAndJoin() }
    }

    @Test fun `cached translations are hidden after model becomes unavailable`() = runTest {
        val provider = FakeProvider()
        val cache = TranslationCache(null)
        val session = TranslationSession(provider, cache)
        var resumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.whileResumed() }
        assertNotNull(session.translate(request()))
        resumed.cancelAndJoin()
        assertNull(session.model.value)
        assertNull(session.translate(request()))
        provider.ready = null
        resumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.whileResumed() }
        assertNull(session.translate(request()))
        assertEquals(1, provider.calls)
        resumed.cancelAndJoin()
    }

    @Test fun `leaving foreground cancels in-flight and queued work`() = runTest {
        val started = CompletableDeferred<Unit>()
        val provider = FakeProvider().apply { operation = { started.complete(Unit); awaitCancellation() } }
        val session = TranslationSession(provider, TranslationCache(null))
        val resumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.whileResumed() }
        val first = launch { session.translate(request()) }
        started.await()
        val queued = launch { session.translate(request(text = "queued")) }
        runCurrent()
        resumed.cancelAndJoin()
        first.join(); queued.join()
        assertTrue(first.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(1, provider.calls)
        assertNull(session.model.value)
    }

    @Test fun `busy retries are bounded and quota stops other automatic work`() = runTest {
        val provider = FakeProvider().apply { operation = { throw FeedbackTranslationException(FeedbackTranslationException.Reason.BUSY) } }
        val session = TranslationSession(provider, TranslationCache(null))
        val resumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.whileResumed() }
        try { session.translate(request()); fail("Expected busy failure") }
        catch (failure: FeedbackTranslationException) { assertEquals(FeedbackTranslationException.Reason.BUSY, failure.reason) }
        assertEquals(3, provider.calls)
        provider.operation = { throw FeedbackTranslationException(FeedbackTranslationException.Reason.QUOTA) }
        try { session.translate(request()); fail("Expected quota failure") }
        catch (failure: FeedbackTranslationException) { assertEquals(FeedbackTranslationException.Reason.QUOTA, failure.reason) }
        assertNull(session.model.value)
        assertNull(session.translate(request(text = "next")))
        assertEquals(4, provider.calls)
        resumed.cancelAndJoin()
    }

    @Test fun `invalid results are not cached and timeout becomes a recoverable failure`() = runTest {
        val provider = FakeProvider().apply { operation = { listOf(FeedbackTranslationField("wrong-id", "hello")) } }
        val session = TranslationSession(provider, TranslationCache(null))
        val resumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.whileResumed() }
        try { session.translate(request()); fail("Expected invalid output") }
        catch (failure: FeedbackTranslationException) { assertEquals(FeedbackTranslationException.Reason.INVALID_RESPONSE, failure.reason) }
        provider.operation = { awaitCancellation() }
        try { session.translate(request()); fail("Expected timeout") }
        catch (failure: FeedbackTranslationException) { assertEquals(FeedbackTranslationException.Reason.FAILURE, failure.reason) }
        assertEquals("nano-v3", session.model.value)
        provider.operation = { it.fields.map { field -> field.copy(text = "Hello") } }
        assertEquals("Hello", session.translate(request())!!.single().text)
        assertEquals(3, provider.calls)
        resumed.cancelAndJoin()
    }

    @Test fun `result is discarded when readiness changes during generation`() = runTest {
        val provider = FakeProvider().apply { operation = { ready = null; fields } }
        val session = TranslationSession(provider, TranslationCache(null))
        val resumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.whileResumed() }
        try { session.translate(request()); fail("Expected loss of model") }
        catch (failure: FeedbackTranslationException) { assertEquals(FeedbackTranslationException.Reason.UNAVAILABLE, failure.reason) }
        assertNull(session.model.value)
        resumed.cancelAndJoin()
    }

    @Test fun `new provider policy does not reuse previous project disk cache`() = runTest {
        val directory = Files.createTempDirectory("translation-policy").toFile()
        try {
            val file = directory.resolve("cache.json")
            val broad = FakeProvider("broad-policy")
            val first = TranslationSession(broad, TranslationCache(file))
            val firstResumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { first.whileResumed() }
            assertEquals("translated Hola", first.translate(request())!!.single().text)
            firstResumed.cancelAndJoin()

            val narrowed = FakeProvider("narrowed-policy").apply { operation = { it.fields } }
            val second = TranslationSession(narrowed, TranslationCache(file))
            val secondResumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { second.whileResumed() }
            assertEquals("Hola", second.translate(request())!!.single().text)
            assertEquals(1, narrowed.calls)
            secondResumed.cancelAndJoin()
        } finally { directory.deleteRecursively() }
    }

    @Test fun `quota suspension blocks queued work in every active scope until a later resume`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = FakeProvider().apply { operation = {
            started.complete(Unit)
            release.await()
            throw FeedbackTranslationException(FeedbackTranslationException.Reason.QUOTA)
        } }
        val cache = TranslationCache(null)
        val list = TranslationSession(provider, cache)
        val detail = TranslationSession(provider, cache)
        val listResumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { list.whileResumed() }
        val detailResumed = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { detail.whileResumed() }
        val first = async { runCatching { list.translate(request()) } }
        started.await()
        val queued = async { runCatching { detail.translate(request(text = "queued")) } }
        runCurrent()
        release.complete(Unit)
        assertEquals(FeedbackTranslationException.Reason.QUOTA, (first.await().exceptionOrNull() as FeedbackTranslationException).reason)
        assertTrue(queued.await().exceptionOrNull() is CancellationException)
        assertNull(list.model.value)
        assertNull(detail.model.value)
        assertEquals(1, provider.calls)
        provider.operation = { it.fields }
        assertNull(detail.translate(request(text = "next")))
        listResumed.cancelAndJoin()
        val resumedAgain = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { list.whileResumed() }
        assertEquals("nano-v3", detail.model.value)
        assertEquals("next", detail.translate(request(text = "next"))!!.single().text)
        assertEquals(2, provider.calls)
        resumedAgain.cancelAndJoin(); detailResumed.cancelAndJoin()
    }

    private class FakeProvider(override val cacheVersion: String = "test-v1") : BuildThisPleaseTranslationProvider("test-project") {
        var ready: String? = "nano-v3"
        var calls = 0
        var operation: suspend (FeedbackTranslationRequest) -> List<FeedbackTranslationField> = { request ->
            request.fields.map { it.copy(text = "translated ${it.text}") }
        }
        override suspend fun availableModel() = ready
        override suspend fun translate(request: FeedbackTranslationRequest): List<FeedbackTranslationField> {
            calls++
            return operation(request)
        }
    }
}
