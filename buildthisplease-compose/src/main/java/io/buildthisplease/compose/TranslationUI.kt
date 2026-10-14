package io.buildthisplease.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.Ticket
import kotlinx.coroutines.CancellationException

private val LocalTranslationSession = staticCompositionLocalOf<TranslationSession?> { null }

@Composable
internal fun FeedbackTranslationScope(
    provider: BuildThisPleaseTranslationProvider?,
    client: BuildThisPleaseClientProtocol,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val session = remember(provider, client) {
        provider?.let { TranslationSession(it, it.cache(context.cacheDir)) }
    }
    LaunchedEffect(session, owner, enabled) {
        if (session != null && enabled) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { session.whileResumed() }
        }
    }
    CompositionLocalProvider(LocalTranslationSession provides if (enabled) session else null, content = content)
}

internal fun Ticket.translationFields() = buildList {
    add(FeedbackTranslationField("title", title))
    add(FeedbackTranslationField("description", description))
    implementationNote?.let { add(FeedbackTranslationField("note", it)) }
}

internal data class TranslatedFeedbackGroup(
    val fields: List<FeedbackTranslationField> = emptyList(),
    val hasTranslation: Boolean = false,
    val showingOriginal: Boolean = false,
    val loading: Boolean = false,
    val canRetry: Boolean = false,
    val toggle: () -> Unit = {},
    val retry: () -> Unit = {},
    val visibilityModifier: Modifier = Modifier,
) {
    fun text(id: String, original: String): String = fields.firstOrNull { it.id == id }?.text ?: original
}

@Composable
internal fun rememberFeedbackTranslation(originals: List<FeedbackTranslationField>, requireVisibility: Boolean = false): TranslatedFeedbackGroup {
    val session = LocalTranslationSession.current ?: return TranslatedFeedbackGroup()
    val model by session.model.collectAsState()
    val target = LocalConfiguration.current.locales[0].toLanguageTag()
    var translated by remember(session, originals, target, model) { mutableStateOf<List<FeedbackTranslationField>?>(null) }
    var loading by remember(session, originals, target, model) { mutableStateOf(false) }
    var failed by remember(session, originals, target, model) { mutableStateOf(false) }
    var original by remember(session, originals, target) { mutableStateOf(false) }
    var retry by remember(session, originals, target) { mutableStateOf(0) }
    var visible by remember(session, originals, requireVisibility) { mutableStateOf(!requireVisibility) }
    LaunchedEffect(session, originals, target, model, retry, visible) {
        val ready = model ?: return@LaunchedEffect
        if (!visible || originals.isEmpty() || originals.all { it.text.isBlank() }) return@LaunchedEffect
        loading = true
        failed = false
        try {
            translated = session.translate(FeedbackTranslationRequest(originals, target, ready))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FeedbackTranslationException) {
            failed = failure.reason !in setOf(
                FeedbackTranslationException.Reason.UNAVAILABLE,
                FeedbackTranslationException.Reason.QUOTA,
                FeedbackTranslationException.Reason.UNSUPPORTED_LANGUAGE,
            )
        } catch (_: Exception) {
            failed = true
        } finally { loading = false }
    }
    val changed = model != null && translated != null && translated != originals
    return TranslatedFeedbackGroup(
        fields = if (changed && !original) translated.orEmpty() else emptyList(),
        hasTranslation = changed,
        showingOriginal = original,
        loading = model != null && loading,
        canRetry = model != null && failed,
        toggle = { original = !original },
        retry = { retry++ },
        visibilityModifier = if (requireVisibility) Modifier.onGloballyPositioned {
            visible = it.isAttached && !it.boundsInWindow().isEmpty
        } else Modifier,
    )
}

@Composable
internal fun FeedbackTranslationDisclosure(group: TranslatedFeedbackGroup, modifier: Modifier = Modifier) {
    if (!group.loading && !group.hasTranslation && !group.canRetry) return
    FlowRow(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            group.loading -> {
                CircularProgressIndicator(Modifier.align(Alignment.CenterVertically).size(12.dp), strokeWidth = 1.5.dp)
                Text(stringResource(R.string.btp_translating), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
            }
            group.hasTranslation -> {
                Text(stringResource(R.string.btp_translated_ai, stringResource(R.string.btp_translated)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(onClick = group.toggle) {
                    Text(stringResource(if (group.showingOriginal) R.string.btp_show_translation else R.string.btp_show_original),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            group.canRetry -> TextButton(onClick = group.retry) {
                Text(stringResource(R.string.btp_try_again), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
