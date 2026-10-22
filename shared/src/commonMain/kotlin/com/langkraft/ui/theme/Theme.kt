package com.langkraft.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// German Immersion Aesthetics: Clean, Functional, High-Contrast
val MidnightBlue = Color(0xFF2D3E50)
val AmberAccent = Color(0xFFE67E22)
val DeepRed = Color(0xFFC0392B)
val SuccessGreen = Color(0xFF27AE60)
val LightGray = Color(0xFFF9FAFB)
val DarkGray = Color(0xFF2C3E50)

private val LightColorPalette = lightColors(
    primary = MidnightBlue,
    primaryVariant = Color(0xFF1A252F),
    secondary = AmberAccent,
    background = LightGray,
    surface = Color.White,
    error = DeepRed,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = DarkGray,
    onSurface = DarkGray
)

private val DarkColorPalette = darkColors(
    primary = Color(0xFF5DADE2),
    primaryVariant = MidnightBlue,
    secondary = AmberAccent,
    background = Color(0xFF17202A),
    surface = Color(0xFF1C2833),
    error = Color(0xFFE74C3C),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

val LangkraftTypography = Typography(
    h4 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = 0.sp
    ),
    h5 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = 0.sp
    ),
    h6 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 0.15.sp
    ),
    subtitle1 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.15.sp
    ),
    body1 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    body2 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    button = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.25.sp
    ),
    caption = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun LangkraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) {
        DarkColorPalette
    } else {
        LightColorPalette
    }

    MaterialTheme(
        colors = colors,
        typography = LangkraftTypography,
        shapes = Shapes(), // Using default constructor
        content = content
    )
}
