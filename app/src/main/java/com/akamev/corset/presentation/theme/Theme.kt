package com.akamev.corset.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE66A24),
    onPrimary = Color.White,
    secondary = Color(0xFF1F7A8C),
    tertiary = Color(0xFFF2B134),
    background = Color(0xFFFFFAF5),
    surface = Color(0xFFFFF4EA),
    surfaceVariant = Color(0xFFFBE6D6),
    outline = Color(0x33A4511F),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF9C66),
    onPrimary = Color(0xFF3F1700),
    secondary = Color(0xFF6CC5D8),
    tertiary = Color(0xFFFFD07A),
    background = Color(0xFF18120E),
    surface = Color(0xFF241A14),
    surfaceVariant = Color(0xFF35261E),
    outline = Color(0x55FFD2B7),
)

private val CorsetTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
)

@Composable
fun CorsetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = CorsetTypography,
        content = content,
    )
}
