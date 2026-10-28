package com.wordcraft.android.keyboard

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive surface for the keyboard: dynamic colour from the wallpaper on Android 12+,
 * a fixed violet seed palette below that, and a forced light/dark override for the keyboard only
 * (a keyboard should match the phone, not the app that happens to be in front).
 */
@Composable
fun WordCraftKeyboardTheme(
    darkOverride: Boolean?,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = darkOverride ?: isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFFA69FFF),
            surface = Color(0xFF1C1D22),
            surfaceContainer = Color(0xFF2F3139),
            surfaceContainerHigh = Color(0xFF3F414C),
            onSurface = Color(0xFFF5F5FA),
            onSurfaceVariant = Color(0xFFC9CAD6)
        )
        else -> lightColorScheme(
            primary = Color(0xFF554EAA),
            surface = Color(0xFFEEF0F6),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFD3D7E4),
            onSurface = Color(0xFF202430),
            onSurfaceVariant = Color(0xFF5A5F70)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
