package io.buildthisplease.nano

import io.buildthisplease.compose.FeedbackTranslationException
import io.buildthisplease.compose.FeedbackTranslationException.Reason
import io.buildthisplease.compose.FeedbackTranslationField
import io.buildthisplease.compose.FeedbackTranslationRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NanoTranslationProviderTest {
    private fun request(text: String = "hola", target: String = "en") =
        FeedbackTranslationRequest(listOf(FeedbackTranslationField("body", text)), target, "nano-v3")
    private fun provider(client: FakeClient) = NanoTranslationProvider("test-project", setOf("en", "es", "zh-Hans", "zh-Hant")) { client }

    @Test fun `only AVAILABLE permits tokenization or inference`() = runTest {
        NanoStatus.entries.forEach { status ->
            val client = FakeClient().apply { readiness = status }
            val provider = provider(client)
            if (status == NanoStatus.AVAILABLE) {
                assertEquals("nano-v3", provider.availableModel())
                assertEquals("hello", provider.translate(request()).single().text)
                assertEquals(1, client.generations)
            } else {
                assertNull(provider.availableModel())
                expectFailure(Reason.UNAVAILABLE) { provider.translate(request()) }
                assertEquals(0, client.generations)
                assertEquals(0, client.tokenCounts)
                assertEquals(0, client.modelNames)
            }
        }
    }

    @Test fun `compiled ML Kit adapter has no download or warmup references`() {
        val bytes = MlKitNanoClient::class.java.getResourceAsStream("MlKitNanoClient.class")!!.use { it.readBytes() }
        val constantPool = bytes.toString(Charsets.ISO_8859_1)
        assertFalse(constantPool.contains("download"))
        assertFalse(constantPool.contains("warmup"))
    }

    @Test fun `loss of model during generation discards result`() = runTest {
        val client = FakeClient().apply { afterGenerate = { readiness = NanoStatus.DOWNLOADABLE } }
        expectFailure(Reason.UNAVAILABLE) { provider(client).translate(request()) }
        assertEquals(1, client.generations)
    }

    @Test fun `model change and closed provider cannot generate`() = runTest {
        val client = FakeClient().apply { model = "nano-v4" }
        val provider = provider(client)
        expectFailure(Reason.UNAVAILABLE) { provider.translate(request()) }
        assertEquals(0, client.generations)
        provider.close(); provider.close()
        assertNull(provider.availableModel())
        expectFailure(Reason.UNAVAILABLE) { provider.translate(request()) }
        assertEquals(1, client.closes)
    }

    @Test fun `closing before first use never constructs a client`() = runTest {
        var created = 0
        val provider = NanoTranslationProvider("test-project", setOf("en")) { created++; FakeClient() }
        provider.close()
        assertNull(provider.availableModel())
        expectFailure(Reason.UNAVAILABLE) { provider.translate(request()) }
        assertEquals(0, created)
    }

    @Test fun `language policy participates in cache identity and is canonicalized`() {
        val broad = NanoTranslationProvider("same-project", setOf("en", "es")) { FakeClient() }
        val reordered = NanoTranslationProvider("same-project", setOf("ES", "EN")) { FakeClient() }
        val narrow = NanoTranslationProvider("same-project", setOf("en")) { FakeClient() }
        assertEquals(broad.cacheVersion, reordered.cacheVersion)
        assertNotEquals(broad.cacheVersion, narrow.cacheVersion)
    }

    @Test fun `readiness is not blocked behind a long inference on shared provider`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FakeClient().apply { beforeGenerate = { started.complete(Unit); release.await() } }
        val provider = provider(client)
        val inference = async { provider.translate(request()) }
        started.await()
        assertEquals("nano-v3", withTimeoutOrNull(1_000) { provider.availableModel() })
        release.complete(Unit)
        assertEquals("hello", inference.await().single().text)
    }

    @Test fun `same-language and uncertain fields remain exact originals`() = runTest {
        val client = FakeClient().apply { status = "original"; source = "en" }
        assertEquals(request("  hello\n\n").fields, provider(client).translate(request("  hello\n\n")))
        client.status = "unsupported"; client.source = "und"
        assertEquals(request("? ambiguous ?").fields, provider(client).translate(request("? ambiguous ?")))
    }

    @Test fun `unsupported target never starts inference and unexpected source is rejected`() = runTest {
        val client = FakeClient()
        expectFailure(Reason.UNSUPPORTED_LANGUAGE) { provider(client).translate(request(target = "fr")) }
        assertEquals(0, client.generations)
        client.source = "fr"
        expectFailure(Reason.INVALID_RESPONSE) { provider(client).translate(request()) }
    }

    @Test fun `same-language rewriting and lost numbers or links are rejected`() = runTest {
        val client = FakeClient().apply { source = "en" }
        expectFailure(Reason.INVALID_RESPONSE) { provider(client).translate(request()) }
        client.source = "es"; client.transform = { "hello" }
        expectFailure(Reason.INVALID_RESPONSE) { provider(client).translate(request("hola 123 https://example.com")) }
    }

    @Test fun `malformed incomplete duplicate and mismatched responses are rejected`() = runTest {
        val variations: List<(String) -> String> = listOf(
            { "not json" },
            { it.replace("\"en\"", "\"fr\"") },
            { it.replace("\"body\"", "\"wrong\"") },
            { it.replace("\"hello\"", "\"\"") },
            { it.replace("\"translated\"", "\"unexpected\"") },
            { it.replace("\"hello\"", "123") },
            { it.replace("\"fields\":[", "\"fields\":[{\"id\":\"body\"},") },
            { "```json\n$it\n```" },
        )
        variations.forEach { variation ->
            expectFailure(Reason.INVALID_RESPONSE) { provider(FakeClient().apply { modify = variation }).translate(request()) }
        }
        expectFailure(Reason.INVALID_RESPONSE) { provider(FakeClient().apply { complete = false }).translate(request()) }
    }

    @Test fun `regional targets and Chinese scripts are preserved`() = runTest {
        assertEquals("hello", provider(FakeClient()).translate(request(target = "en-US")).single().text)
        assertEquals("hello", provider(FakeClient()).translate(request(target = "en-US-u-hc-h12")).single().text)
        val client = FakeClient().apply { source = "zh-Hans"; transform = { "繁體" } }
        assertEquals("繁體", provider(client).translate(request("简体", "zh-Hant")).single().text)
        expectFailure(Reason.INVALID_RESPONSE) { provider(client).translate(request("简体", "zh-Hans")) }
    }

    @Test fun `oversized text is chunked without truncating paragraph boundaries or empty fields`() = runTest {
        val client = FakeClient().apply { estimate = { it.length / 3 }; transform = { it.replace("hola", "hello") } }
        val text = "hola ".repeat(500) + "\n\n" + "hola ".repeat(500)
        val request = request(text).copy(fields = request(text).fields + FeedbackTranslationField("empty", "  "))
        val result = provider(client).translate(request)
        assertEquals(text.replace("hola", "hello"), result[0].text)
        assertEquals("  ", result[1].text)
        assertTrue(client.generations > 1)
        assertTrue(client.outputLimits.all { it in 1..4_000 })
    }

    @Test fun `split preserves UTF16 pairs and makes progress`() {
        listOf("a🙂b", "🙂🙂", "first\n\nsecond", "one two three", "abc").forEach { text ->
            val parts = splitText(text)
            assertEquals(text, parts.joinToString(""))
            assertTrue(parts.all { it.isNotEmpty() && it.length < text.length })
            assertFalse(parts[0].last().isHighSurrogate())
            assertFalse(parts[1].first().isLowSurrogate())
        }
    }

    @Test fun `whole-field validation rejects a rewritten link tail after chunk joining`() = runTest {
        val url = "https://example.com/" + "a".repeat(5_000)
        val client = FakeClient().apply {
            estimate = { it.length / 3 }
            transform = { if (it.startsWith("https://")) it else it.replace("a", "b") }
        }
        expectFailure(Reason.INVALID_RESPONSE) { provider(client).translate(request(url)) }
        assertTrue(client.generations > 1)
    }

    @Test fun `complete numeric tokens and occurrence counts must be preserved`() = runTest {
        listOf(
            "precio 10" to "price 100",
            "10 y 10" to "10",
            "−10" to "10",
            "١٢" to "13",
            "hola" to "hello 2",
        ).forEach { (original, changed) ->
            expectFailure(Reason.INVALID_RESPONSE) { provider(FakeClient().apply { transform = { changed } }).translate(request(original)) }
        }
        val client = FakeClient().apply { transform = { "price 10 and 10" } }
        assertEquals("price 10 and 10", provider(client).translate(request("precio 10 y 10")).single().text)
    }

    @Test fun `removed added and normalized internal line separators are rejected`() = runTest {
        listOf(
            "hola\n\nhola" to "hello world",
            "hola hola" to "hello\nhello",
            "hola\r\nhola" to "hello\nhello",
            "hola\nhola" to "hello\n\nhello",
        ).forEach { (original, changed) ->
            expectFailure(Reason.INVALID_RESPONSE) {
                provider(FakeClient().apply { transform = { changed } }).translate(request(original))
            }
        }
        val original = "  hola\r\n\r\nhola\nhola  "
        assertEquals(original.replace("hola", "hello"), provider(FakeClient()).translate(request(original)).single().text)
    }

    @Test fun `all URI schemes retain exact destinations and occurrences`() = runTest {
        listOf(
            "HTTPS://EXAMPLE.COM/request",
            "buildthisplease://request/new",
            "mailto:help@example.com",
            "tel:+123456789",
            "urn:example:request",
        ).forEach { uri ->
            val original = "hola $uri"
            assertEquals("hello $uri", provider(FakeClient()).translate(request(original)).single().text)
            expectFailure(Reason.INVALID_RESPONSE) {
                provider(FakeClient().apply { transform = { "hello ${uri}changed" } }).translate(request(original))
            }
            expectFailure(Reason.INVALID_RESPONSE) {
                provider(FakeClient().apply { transform = { "hello" } }).translate(request(original))
            }
        }
    }

    @Test fun `explicit regional variant and extension allowlists are respected`() = runTest {
        val client = FakeClient().apply { source = "es-ES" }
        val regional = NanoTranslationProvider("regional", setOf("en-US", "es-ES")) { client }
        expectFailure(Reason.UNSUPPORTED_LANGUAGE) { regional.translate(request(target = "en-GB")) }
        assertEquals(0, client.generations)
        assertEquals("hello", regional.translate(request(target = "en-US")).single().text)
        client.source = "es-MX"
        expectFailure(Reason.INVALID_RESPONSE) { regional.translate(request(target = "en-US")) }

        val restricted = NanoTranslationProvider("variants", setOf("en-US-posix-u-hc-h12", "es")) { FakeClient() }
        expectFailure(Reason.UNSUPPORTED_LANGUAGE) { restricted.translate(request(target = "en-US")) }
        expectFailure(Reason.UNSUPPORTED_LANGUAGE) { restricted.translate(request(target = "en-US-posix-u-hc-h23")) }
        assertEquals("hello", restricted.translate(request(target = "en-US-posix-u-hc-h12")).single().text)
        assertEquals("hello", provider(FakeClient().apply { source = "es-MX" }).translate(request(target = "en-GB")).single().text)
    }

    private suspend fun expectFailure(reason: Reason, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (failure: FeedbackTranslationException) { assertEquals(reason, failure.reason) }
    }

    private class FakeClient : NanoClient {
        var readiness = NanoStatus.AVAILABLE
        var model = "nano-v3"
        var status = "translated"
        var source = "es"
        var complete = true
        var generations = 0
        var tokenCounts = 0
        var modelNames = 0
        var closes = 0
        var estimate: (String) -> Int = { 100 }
        var transform: (String) -> String = { it.replace("hola", "hello") }
        var modify: (String) -> String = { it }
        var afterGenerate: () -> Unit = {}
        var beforeGenerate: suspend () -> Unit = {}
        val outputLimits = mutableListOf<Int>()
        override suspend fun status() = readiness
        override suspend fun modelName(): String { modelNames++; return model }
        override suspend fun tokenLimit() = 4_000
        override suspend fun countTokens(prompt: String): Int { tokenCounts++; return estimate(prompt) }
        override suspend fun generate(prompt: String, maxOutputTokens: Int): NanoResponse {
            generations++; outputLimits += maxOutputTokens
            beforeGenerate()
            val input = Json.parseToJsonElement(prompt.substringAfter("Input JSON:\n")).jsonObject
            val response = buildJsonObject {
                put("targetLanguage", input.getValue("targetLanguage"))
                put("fields", JsonArray(input.getValue("fields").jsonArray.map { value ->
                    val field = value.jsonObject
                    val original = field.getValue("text").jsonPrimitive.content
                    buildJsonObject {
                        put("id", field.getValue("id"))
                        put("sourceLanguage", source)
                        put("status", status)
                        put("text", if (status == "translated") transform(original) else original)
                    }
                }))
            }.toString()
            afterGenerate()
            return NanoResponse(modify(response), complete)
        }
        override fun close() { closes++ }
    }
}
