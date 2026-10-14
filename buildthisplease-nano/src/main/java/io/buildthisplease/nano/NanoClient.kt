package io.buildthisplease.nano

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.TextPart
import io.buildthisplease.compose.FeedbackTranslationException
import io.buildthisplease.compose.FeedbackTranslationException.Reason
import java.time.Duration
import kotlinx.coroutines.CancellationException

internal enum class NanoStatus { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }
internal data class NanoResponse(val text: String, val complete: Boolean)

/** Intentionally excludes any model-download or installation operation. */
internal interface NanoClient {
    suspend fun status(): NanoStatus
    suspend fun modelName(): String
    suspend fun tokenLimit(): Int
    suspend fun countTokens(prompt: String): Int
    suspend fun generate(prompt: String, maxOutputTokens: Int): NanoResponse
    fun close()
}

internal class MlKitNanoClient : NanoClient {
    private val model = Generation.getClient()

    override suspend fun status(): NanoStatus = api {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            else -> NanoStatus.UNAVAILABLE
        }
    }

    override suspend fun modelName(): String = api { model.getBaseModelName() }
    override suspend fun tokenLimit(): Int = api { model.getTokenLimit() }
    override suspend fun countTokens(prompt: String): Int = api {
        model.countTokens(GenerateContentRequest.Builder(TextPart(prompt)).build()).totalTokens
    }

    override suspend fun generate(prompt: String, maxOutputTokens: Int): NanoResponse = api {
        val request = GenerateContentRequest.Builder(TextPart(prompt)).apply {
            temperature = 0.0f
            candidateCount = 1
            this.maxOutputTokens = maxOutputTokens
        }.build()
        val candidate = model.generateContent(request).candidates.singleOrNull()
            ?: throw FeedbackTranslationException(Reason.INVALID_RESPONSE)
        NanoResponse(candidate.text, candidate.finishReason == Candidate.FinishReason.STOP)
    }

    override fun close() = model.close()

    private suspend fun <T> api(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: GenAiException) { throw nanoFailure(failure) }
}

internal fun nanoFailure(failure: GenAiException) = FeedbackTranslationException(when (failure.errorCode) {
    // A positive retry delay identifies quota exhaustion, rather than temporary contention.
    GenAiException.ErrorCode.BUSY -> if (failure.retryDelay > Duration.ZERO) Reason.QUOTA else Reason.BUSY
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> Reason.QUOTA
    GenAiException.ErrorCode.NOT_AVAILABLE,
    GenAiException.ErrorCode.NOT_SUPPORTED,
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
    GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> Reason.UNAVAILABLE
    else -> Reason.FAILURE
})
