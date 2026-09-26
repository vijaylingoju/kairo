package ai.kairo.gallery.settings.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext

/**
 * Standalone entry for the Settings Assistant while the modules are built in parallel.
 * The bubble / main screen can later call [SettingsChatScreen] or the SettingsAgent directly.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) { SettingsChatScreen() }
        }
    }
}
