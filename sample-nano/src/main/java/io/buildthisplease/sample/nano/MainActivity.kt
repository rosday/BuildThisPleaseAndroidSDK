package io.buildthisplease.sample.nano

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.buildthisplease.compose.BuildThisPleaseFeedback
import io.buildthisplease.core.MockBuildThisPleaseClient
import io.buildthisplease.nano.NanoTranslationProvider

/** Mock feedback, real availability check. Never requests model installation or downloads. */
class MainActivity : ComponentActivity() {
    private val client = MockBuildThisPleaseClient()
    // Demo scope only; consuming apps must evaluate their own language set on supported devices.
    private val translations = NanoTranslationProvider("nano-demo", setOf("en", "es"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { BuildThisPleaseFeedback(client, translationProvider = translations) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        translations.close()
    }
}
