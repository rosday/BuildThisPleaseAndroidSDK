package io.buildthisplease.compose

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow

/** One retained provider per feedback client/project, shared by list and detail destinations.
 *
 * [cacheNamespace] must identify the project (for example its publishable project key). Providers
 * must only use models that are already ready; checking availability must not request a download.
 * The host owns the provider and closes it after its feedback destinations have been disposed.
 */
abstract class BuildThisPleaseTranslationProvider(val cacheNamespace: String) : AutoCloseable {
    init { require(cacheNamespace.isNotBlank()) { "A project cache namespace is required." } }

    /** Change this when the prompt or response format changes. */
    abstract val cacheVersion: String

    /** Returns an already-available model's identifier, or null. Never downloads model assets. */
    abstract suspend fun availableModel(): String?

    /** Returns every requested field, preserving IDs and originals when no translation is needed. */
    abstract suspend fun translate(request: FeedbackTranslationRequest): List<FeedbackTranslationField>

    override fun close() = Unit

    internal val executionMutex = Mutex()
    internal val quotaSuspended = MutableStateFlow(false)
    private var sharedCache: TranslationCache? = null

    @Synchronized
    internal fun cache(directory: File): TranslationCache = sharedCache ?: TranslationCache(
        File(directory, "BuildThisPlease/translations/${translationHash(cacheNamespace)}.json"),
    ).also { sharedCache = it }
}

data class FeedbackTranslationField(val id: String, val text: String)

data class FeedbackTranslationRequest(
    val fields: List<FeedbackTranslationField>,
    val targetLanguageTag: String,
    val modelId: String,
)

/** Reasons deliberately contain no feedback text or model response. */
class FeedbackTranslationException(val reason: Reason) : Exception("Translation ${reason.name.lowercase()}.") {
    enum class Reason { UNAVAILABLE, BUSY, QUOTA, INVALID_RESPONSE, UNSUPPORTED_LANGUAGE, FAILURE }
}
