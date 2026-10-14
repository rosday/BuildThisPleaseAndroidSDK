package io.buildthisplease.compose

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.buildthisplease.core.MockBuildThisPleaseClient
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.Comment
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
@LooperMode(LooperMode.Mode.PAUSED)
class TranslationUITest {
    @get:Rule val compose = createComposeRule()

    @Test fun availableModelTranslatesAndOriginalToggleWorksAtLargeFontScale() {
        val provider = FakeProvider()
        val client = MockBuildThisPleaseClient()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    FeedbackTranslationScope(provider, client) {
                        val group = rememberFeedbackTranslation(listOf(FeedbackTranslationField("title", "Hola")))
                        Column { Text(group.text("title", "Hola")); FeedbackTranslationDisclosure(group) }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Translated Hola").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Translated · AI").assertIsDisplayed()
        compose.onNodeWithText("Show original").performClick()
        compose.onNodeWithText("Hola").assertIsDisplayed()
        compose.onNodeWithText("Show translation").performClick()
        compose.onNodeWithText("Translated Hola").assertIsDisplayed()
        assertEquals(1, provider.calls)
    }

    @Test fun unavailableModelLeavesOriginalsWithoutTranslationControlsOnApi24() {
        val provider = FakeProvider().apply { model = null }
        val client = MockBuildThisPleaseClient()
        compose.setContent { MaterialTheme { BuildThisPleaseFeedback(client, translationProvider = provider) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Filter requests by status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Filter requests by status").assertIsDisplayed()
        compose.onAllNodesWithText("Show original").assertCountEquals(0)
        compose.onAllNodesWithText("Translating…").assertCountEquals(0)
        assertEquals(0, provider.calls)
    }

    @Test fun changedContentCannotShowPreviousTranslationAndDisabledScopeHidesCachedResult() {
        val provider = FakeProvider()
        val client = MockBuildThisPleaseClient()
        val text = mutableStateOf("Hola")
        val enabled = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                FeedbackTranslationScope(provider, client, enabled.value) {
                    val group = rememberFeedbackTranslation(listOf(FeedbackTranslationField("title", text.value)))
                    Column { Text(group.text("title", text.value)); FeedbackTranslationDisclosure(group) }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Translated Hola").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { text.value = "Edited" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Translated Edited").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Translated Hola").assertCountEquals(0)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("Edited").assertIsDisplayed()
        compose.onAllNodesWithText("Show original").assertCountEquals(0)
    }

    @Test fun hiddenMessagesAreExcludedAndEditingUsesOriginalText() {
        val provider = FakeProvider()
        val ticket = MockBuildThisPleaseClient.sampleTickets().first()
        val date = ticket.createdAt
        val comment = Comment("mine", ticket.id, Comment.Author.USER, "Mi mensaje", false, false, true, true, date, date)
        val hidden = comment.copy(id = "hidden", body = "Hidden original", isHidden = true)
        val base = MockBuildThisPleaseClient(listOf(ticket), mapOf(ticket.id to listOf(comment, hidden)))
        var submitted: String? = null
        val client = object : BuildThisPleaseClientProtocol by base {
            override suspend fun updateComment(ticketId: String, commentId: String, body: String): Comment {
                submitted = body
                return base.updateComment(ticketId, commentId, body)
            }
        }
        compose.setContent {
            MaterialTheme { BuildThisPleaseTicketDetail(client, ticket.id, onBack = {}, translationProvider = provider) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Translated Mi mensaje").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Message removed").assertIsDisplayed()
        assertEquals(false, provider.seenTexts.contains("Hidden original"))
        compose.onNodeWithText("Translated Mi mensaje").performTouchInput { longClick() }
        compose.onNodeWithText("Edit message").performClick()
        compose.onNodeWithText("Mi mensaje").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { submitted != null }
        assertEquals("Mi mensaje", submitted)
    }

    @Test fun offscreenContentDoesNotStartInference() {
        val provider = FakeProvider()
        val client = MockBuildThisPleaseClient()
        compose.setContent {
            MaterialTheme {
                FeedbackTranslationScope(provider, client) {
                    Column(Modifier.height(48.dp).clipToBounds()) {
                        Spacer(Modifier.height(200.dp))
                        val group = rememberFeedbackTranslation(listOf(FeedbackTranslationField("body", "Offscreen")), requireVisibility = true)
                        Text(group.text("body", "Offscreen"), group.visibilityModifier)
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, provider.calls)
    }

    @Test fun inPlaceLocaleChangeDoesNotResetQuota() {
        val provider = FakeProvider().apply { exhaustQuotaOnce = true }
        val client = MockBuildThisPleaseClient()
        val configuration = mutableStateOf(Configuration().apply { setLocales(LocaleList.forLanguageTags("en")) })
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalConfiguration provides configuration.value, LocalLifecycleOwner provides owner) {
                    FeedbackTranslationScope(provider, client) {
                        val group = rememberFeedbackTranslation(listOf(FeedbackTranslationField("title", "Hola")))
                        Column { Text(group.text("title", "Hola")); FeedbackTranslationDisclosure(group) }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { provider.quotaSuspended.value }
        compose.runOnIdle {
            configuration.value = Configuration(configuration.value).apply { setLocales(LocaleList.forLanguageTags("fr")) }
        }
        compose.waitForIdle()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        assertEquals(true, provider.quotaSuspended.value)
        assertEquals(1, provider.calls)
        compose.onNodeWithText("Hola").assertIsDisplayed()
        compose.onAllNodesWithText("Show original").assertCountEquals(0)

        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Translated Hola").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(false, provider.quotaSuspended.value)
        assertEquals(2, provider.calls)
    }

    private class FakeProvider : BuildThisPleaseTranslationProvider(UUID.randomUUID().toString()) {
        override val cacheVersion = "ui-test-v1"
        var model: String? = "fake-ready-model"
        var calls = 0
        var exhaustQuotaOnce = false
        val seenTexts = mutableSetOf<String>()
        override suspend fun availableModel() = model
        override suspend fun translate(request: FeedbackTranslationRequest) = request.fields.map {
            calls++
            if (exhaustQuotaOnce) {
                exhaustQuotaOnce = false
                throw FeedbackTranslationException(FeedbackTranslationException.Reason.QUOTA)
            }
            seenTexts += it.text
            it.copy(text = "Translated ${it.text}")
        }
    }
}
