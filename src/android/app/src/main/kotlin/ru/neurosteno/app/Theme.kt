package ru.neurosteno.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Спокойная палитра: белый/чёрный фон, сгруппированные светло-серые поверхности, тёплый красный акцент записи. */
object Palette {
    val Record = Color(0xFFF2483E)
    val RecordDark = Color(0xFFFF5A50)
    val LightBg = Color(0xFFFFFFFF)
    val LightGroup = Color(0xFFF3F3F6)
    val LightLine = Color(0xFFE3E3E8)
    val DarkBg = Color(0xFF000000)
    val DarkGroup = Color(0xFF1C1C1F)
    val DarkLine = Color(0xFF2E2E33)
    val Secondary = Color(0xFF8A8A90)
}

private val Light = lightColorScheme(
    primary = Palette.Record,
    onPrimary = Color.White,
    background = Palette.LightBg,
    onBackground = Color(0xFF111114),
    surface = Palette.LightBg,
    onSurface = Color(0xFF111114),
    surfaceVariant = Palette.LightGroup,
    onSurfaceVariant = Palette.Secondary,
    outlineVariant = Palette.LightLine,
    secondary = Color(0xFF2F6FED),
)

private val Dark = darkColorScheme(
    primary = Palette.RecordDark,
    onPrimary = Color.White,
    background = Palette.DarkBg,
    onBackground = Color(0xFFF2F2F5),
    surface = Palette.DarkBg,
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Palette.DarkGroup,
    onSurfaceVariant = Palette.Secondary,
    outlineVariant = Palette.DarkLine,
    secondary = Color(0xFF5B8DF5),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    headlineMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NeuroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppTypography, content = content)
}

/** Цвета говорящих — приглушённые, различимые в светлой и тёмной теме. */
private val speakerColors = listOf(
    Color(0xFF2F6FED), Color(0xFFE0533D), Color(0xFF2E9E5B), Color(0xFFD98A12),
    Color(0xFF8B5CF6), Color(0xFF0E9AA7), Color(0xFFB4537A), Color(0xFF6B7280),
)

fun speakerColor(id: String): Color {
    val n = id.removePrefix("speaker_").toIntOrNull() ?: 1
    return speakerColors[(n - 1).mod(speakerColors.size)]
}

fun speakerName(id: String): String = id.replace("speaker_", "Говорящий ")

/** 1:05.3 / 12:04 / 1:02:03 — компактно, без ведущих нулей у минут. */
fun fmtMs(ms: Long, tenths: Boolean = true): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        tenths -> "%d:%02d.%d".format(s / 60, s % 60, ms % 1000 / 100)
        else -> "%d:%02d".format(s / 60, s % 60)
    }
}
