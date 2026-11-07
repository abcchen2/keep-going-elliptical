package com.keepgoing.elliptical.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B3D0E),
    secondary = Color(0xFF00796B),
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7F6),
    error = Color(0xFFC62828),
)

@Composable
fun KeepGoingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
