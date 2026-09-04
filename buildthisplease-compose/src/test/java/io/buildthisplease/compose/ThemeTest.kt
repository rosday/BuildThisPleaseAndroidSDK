package io.buildthisplease.compose

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test
    fun `iOS system blue uses black content in light mode`() {
        assertEquals(Color.Black, Color(0xFF007AFF).contrastingContentColor())
    }

    @Test
    fun `iOS system blue uses black content in dark mode`() {
        assertEquals(Color.Black, Color(0xFF0A84FF).contrastingContentColor())
    }

    @Test
    fun `dark accents still use white content`() {
        assertEquals(Color.White, Color(0xFF004080).contrastingContentColor())
    }
}
