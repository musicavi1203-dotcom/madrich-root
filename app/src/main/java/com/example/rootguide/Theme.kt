package com.example.rootguide

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun RootGuideTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF90CAF9),
            onPrimary = Color(0xFF003258),
            error = Color(0xFFEF9A9A)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1E88E5),
            onPrimary = Color.White,
            error = Color(0xFFD32F2F)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
