package io.buildthisplease.nano

import com.google.mlkit.genai.common.GenAiException
import io.buildthisplease.compose.FeedbackTranslationException.Reason
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class NanoClientTest {
    @Test fun `quota busy errors suspend while ordinary busy errors remain retryable`() {
        assertEquals(Reason.QUOTA, nanoFailure(GenAiException(null, GenAiException.ErrorCode.BUSY, Duration.ofSeconds(30))).reason)
        assertEquals(Reason.QUOTA, nanoFailure(GenAiException(null, GenAiException.ErrorCode.BUSY, Duration.ofNanos(1))).reason)
        assertEquals(Reason.BUSY, nanoFailure(GenAiException(null, GenAiException.ErrorCode.BUSY, Duration.ZERO)).reason)
        assertEquals(Reason.QUOTA, nanoFailure(GenAiException(null, GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED)).reason)
    }
}
