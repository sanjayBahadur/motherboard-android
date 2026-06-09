package com.motherboard.focus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MotherboardColorScheme = darkColorScheme(
    primary = Evergreen80,
    secondary = Sand80,
    tertiary = Ember80,
)

@Composable
fun MotherboardTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MotherboardColorScheme,
        typography = Typography,
        content = content,
    )
}
