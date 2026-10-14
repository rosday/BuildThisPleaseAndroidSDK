package io.buildthisplease.compose

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Screen lifetime and presentation eligibility. Methods are called on the UI dispatcher. */
internal class TranslationSession(
    private val provider: BuildThisPleaseTranslationProvider,
    private val cache: TranslationCache,
) {
    private val mutableModel = MutableStateFlow<String?>(null)
    val model = mutableModel.asStateFlow()
    private var active = false
    private val requests = mutableSetOf<Job>()

    suspend fun whileResumed() {
        active = true
        // A later screen resume is the explicit retry boundary shared by this provider's scopes.
        provider.quotaSuspended.value = false
        try {
            provider.quotaSuspended.collect { suspended ->
                if (suspended) mutableModel.value = null
                else {
                    val ready = readyModel()
                    mutableModel.value = if (provider.quotaSuspended.value) null else ready
                }
            }
        } finally {
            active = false
            mutableModel.value = null
            requests.toList().forEach { it.cancel() }
        }
    }

    suspend fun translate(request: FeedbackTranslationRequest): List<FeedbackTranslationField>? {
        if (!eligible(request.modelId)) return null
        val job = currentCoroutineContext().job
        requests += job
        try {
            return provider.executionMutex.withLock {
                ensureEligible(request.modelId)
                val ready = readyModel()
                if (ready != request.modelId) {
                    mutableModel.value = ready
                    throw FeedbackTranslationException(FeedbackTranslationException.Reason.UNAVAILABLE)
                }
                val key = translationHash(provider.cacheVersion, request.modelId, request.targetLanguageTag,
                    *request.fields.flatMap { listOf(it.id, it.text) }.toTypedArray())
                cache.get(key, request.fields)?.let { ensureEligible(request.modelId); return@withLock it }
                var attempt = 0
                lateinit var result: List<FeedbackTranslationField>
                while (true) {
                    ensureEligible(request.modelId)
                    try {
                        result = withTimeoutOrNull(60_000) { provider.translate(request) }
                            ?: throw FeedbackTranslationException(FeedbackTranslationException.Reason.FAILURE)
                        break
                    } catch (failure: FeedbackTranslationException) {
                        if (failure.reason == FeedbackTranslationException.Reason.BUSY && attempt < 2) {
                            delay(250L shl attempt++)
                        } else {
                            if (failure.reason == FeedbackTranslationException.Reason.QUOTA) provider.quotaSuspended.value = true
                            if (failure.reason in setOf(FeedbackTranslationException.Reason.UNAVAILABLE, FeedbackTranslationException.Reason.QUOTA)) {
                                mutableModel.value = null
                            }
                            throw failure
                        }
                    }
                }
                ensureEligible(request.modelId)
                val stillReady = readyModel()
                if (stillReady != request.modelId) {
                    mutableModel.value = stillReady
                    throw FeedbackTranslationException(FeedbackTranslationException.Reason.UNAVAILABLE)
                }
                if (result.map { it.id } != request.fields.map { it.id } || result.zip(request.fields).any { (value, original) ->
                        value.text.isBlank() && original.text.isNotBlank()
                    }) throw FeedbackTranslationException(FeedbackTranslationException.Reason.INVALID_RESPONSE)
                cache.put(key, request.fields, result)
                ensureEligible(request.modelId)
                result
            }
        } finally { requests -= job }
    }

    private suspend fun readyModel(): String? = withTimeoutOrNull(5_000) {
        try { provider.availableModel()?.takeIf(String::isNotBlank) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private fun eligible(modelId: String) = active && !provider.quotaSuspended.value && mutableModel.value == modelId

    private suspend fun ensureEligible(modelId: String) {
        currentCoroutineContext().ensureActive()
        if (!eligible(modelId)) throw CancellationException("Translation screen is no longer eligible.")
    }
}
