package io.buildthisplease.compose

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val BuildThisPleaseDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
)

private val BuildThisPleaseLightColorScheme = lightColorScheme(
    primary = Color(0xFF6650A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
)

@Immutable
data class BuildThisPleaseTheme(
    val accent: Color = Color(0xFF5B5CE2),
    val voteHighlight: Color = accent,
)

internal val LocalBuildThisPleaseTheme = staticCompositionLocalOf { BuildThisPleaseTheme() }
private val LocalBuildThisPleaseDarkTheme = staticCompositionLocalOf { false }

/**
 * Material theme used by the SDK and available to sample or host UI that should match it.
 * It follows system dark mode and, like PepFlow, uses dynamic color on Android 12+.
 */
@Composable
fun BuildThisPleaseMaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> BuildThisPleaseDarkColorScheme
        else -> BuildThisPleaseLightColorScheme
    }
    val colorScheme = accent?.let { color ->
        baseScheme.copy(
            primary = color,
            onPrimary = color.contrastingContentColor(),
        )
    } ?: baseScheme

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalBuildThisPleaseDarkTheme provides darkTheme, content = content)
    }
}

internal fun Color.contrastingContentColor(): Color {
    val backgroundLuminance = luminance()
    val blackContrast = (backgroundLuminance + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (backgroundLuminance + 0.05f)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

/** Matches the section hierarchy used by PepFlow's Android UI. */
internal val ColorScheme.buildThisPleaseSectionContainer: Color
    get() = surfaceContainerHigh

internal val ColorScheme.buildThisPleaseConversationBubble: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalBuildThisPleaseDarkTheme.current) surfaceBright else Color.White

internal val ColorScheme.buildThisPleaseComposerBackground: Color
    get() = surfaceContainer

internal val ColorScheme.buildThisPleaseComposerField: Color
    get() = surfaceContainerHighest
