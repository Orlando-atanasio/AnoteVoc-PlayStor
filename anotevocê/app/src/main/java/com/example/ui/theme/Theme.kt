package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.domain.model.AppThemeMode

@Immutable
data class AnoteVoceSurfaceTokens(
  val themeMode: AppThemeMode,
  val isDark: Boolean,
  val drawerContainer: Color,
  val cardContainer: Color,
  val cardBorder: Color,
  val selectedIndicator: Color,
  val atmosphereTint: Color,
  val subtleRuleLine: Color,
  val greetingBannerContainer: Color,
  val greetingBannerContent: Color,
)

val LocalAnoteVoceSurfaceTokens =
  staticCompositionLocalOf {
    daySurfaceTokens()
  }

private val DayColorScheme: ColorScheme =
  lightColorScheme(
    primary = DayPrimary,
    onPrimary = DayOnPrimary,
    primaryContainer = DayPrimaryContainer,
    onPrimaryContainer = DayOnPrimaryContainer,
    secondary = DaySecondary,
    onSecondary = DayOnSecondary,
    secondaryContainer = DaySecondaryContainer,
    onSecondaryContainer = DayOnSecondaryContainer,
    tertiary = DayTertiary,
    onTertiary = DayOnTertiary,
    tertiaryContainer = DayTertiaryContainer,
    onTertiaryContainer = DayOnTertiaryContainer,
    background = DayBackground,
    onBackground = DayOnBackground,
    surface = DaySurface,
    onSurface = DayOnBackground,
    surfaceVariant = DaySurfaceVariant,
    onSurfaceVariant = DayOnSurfaceVariant,
    outline = DayOutline,
    outlineVariant = DayOutlineVariant,
  )

private val NightColorScheme: ColorScheme =
  darkColorScheme(
    primary = NightSlatePrimary,
    onPrimary = NightSlateOnPrimary,
    primaryContainer = NightSlatePrimaryContainer,
    onPrimaryContainer = NightSlateOnPrimaryContainer,
    secondary = NightGraphiteSecondary,
    onSecondary = NightGraphiteOnSecondary,
    secondaryContainer = NightGraphiteSecondaryContainer,
    onSecondaryContainer = NightGraphiteOnSecondaryContainer,
    tertiary = NightAmberTertiary,
    onTertiary = NightAmberOnTertiary,
    tertiaryContainer = NightAmberTertiaryContainer,
    onTertiaryContainer = NightAmberOnTertiaryContainer,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnBackground,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
  )

private val NaturalColorScheme: ColorScheme =
  lightColorScheme(
    primary = NaturalPrimary,
    onPrimary = NaturalOnPrimary,
    primaryContainer = NaturalPrimaryContainer,
    onPrimaryContainer = NaturalOnPrimaryContainer,
    secondary = NaturalSecondary,
    onSecondary = NaturalOnSecondary,
    secondaryContainer = NaturalSecondaryContainer,
    onSecondaryContainer = NaturalOnSecondaryContainer,
    tertiary = NaturalTertiary,
    onTertiary = NaturalOnTertiary,
    tertiaryContainer = NaturalTertiaryContainer,
    onTertiaryContainer = NaturalOnTertiaryContainer,
    background = NaturalBackground,
    onBackground = NaturalOnBackground,
    surface = NaturalSurface,
    onSurface = NaturalOnBackground,
    surfaceVariant = NaturalSurfaceVariant,
    onSurfaceVariant = NaturalOnSurfaceVariant,
    outline = NaturalOutline,
    outlineVariant = NaturalOutlineVariant,
  )

private val SkyColorScheme: ColorScheme =
  lightColorScheme(
    primary = SkyPrimary,
    onPrimary = SkyOnPrimary,
    primaryContainer = SkyPrimaryContainer,
    onPrimaryContainer = SkyOnPrimaryContainer,
    secondary = SkySecondary,
    onSecondary = SkyOnSecondary,
    secondaryContainer = SkySecondaryContainer,
    onSecondaryContainer = SkyOnSecondaryContainer,
    tertiary = SkyTertiary,
    onTertiary = SkyOnTertiary,
    tertiaryContainer = SkyTertiaryContainer,
    onTertiaryContainer = SkyOnTertiaryContainer,
    background = SkyBackground,
    onBackground = SkyOnBackground,
    surface = SkySurface,
    onSurface = SkyOnBackground,
    surfaceVariant = SkySurfaceVariant,
    onSurfaceVariant = SkyOnSurfaceVariant,
    outline = SkyOutline,
    outlineVariant = SkyOutlineVariant,
  )

private fun daySurfaceTokens() =
  AnoteVoceSurfaceTokens(
    themeMode = AppThemeMode.MODO_DIA,
    isDark = false,
    drawerContainer = Color(0xFFF5F0E8),
    cardContainer = DaySurface,
    cardBorder = DayOutlineVariant,
    selectedIndicator = DayPrimary,
    atmosphereTint = Color(0xFFEEE6D8),
    subtleRuleLine = DayOutlineVariant.copy(alpha = 0.55f),
    greetingBannerContainer = DayPrimaryContainer.copy(alpha = 0.65f),
    greetingBannerContent = DayOnPrimaryContainer,
  )

private fun nightSurfaceTokens() =
  AnoteVoceSurfaceTokens(
    themeMode = AppThemeMode.MODO_NOITE,
    isDark = true,
    drawerContainer = Color(0xFF141C24),
    cardContainer = NightSurface,
    cardBorder = NightOutlineVariant,
    selectedIndicator = NightSlatePrimary,
    atmosphereTint = Color(0xFF1A2531),
    subtleRuleLine = NightOutlineVariant.copy(alpha = 0.45f),
    greetingBannerContainer = NightSlatePrimaryContainer.copy(alpha = 0.72f),
    greetingBannerContent = NightSlateOnPrimaryContainer,
  )

private fun naturalSurfaceTokens() =
  AnoteVoceSurfaceTokens(
    themeMode = AppThemeMode.MODO_NATURAL,
    isDark = false,
    drawerContainer = Color(0xFFECE8DC),
    cardContainer = NaturalSurface,
    cardBorder = NaturalOutlineVariant,
    selectedIndicator = NaturalPrimary,
    atmosphereTint = Color(0xFFE4E7DD),
    subtleRuleLine = NaturalOutlineVariant.copy(alpha = 0.60f),
    greetingBannerContainer = NaturalPrimaryContainer.copy(alpha = 0.70f),
    greetingBannerContent = NaturalOnPrimaryContainer,
  )

private fun skySurfaceTokens() =
  AnoteVoceSurfaceTokens(
    themeMode = AppThemeMode.MODO_CEU,
    isDark = false,
    drawerContainer = Color(0xFFE7F0FA),
    cardContainer = SkySurface,
    cardBorder = SkyOutlineVariant,
    selectedIndicator = SkyPrimary,
    atmosphereTint = Color(0xFFDCEBFB),
    subtleRuleLine = SkyOutlineVariant.copy(alpha = 0.60f),
    greetingBannerContainer = SkyPrimaryContainer.copy(alpha = 0.70f),
    greetingBannerContent = SkyOnPrimaryContainer,
  )

@Composable
fun AnoteVoceTheme(
  themeMode: AppThemeMode = AppThemeMode.MODO_DIA,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when (themeMode) {
      AppThemeMode.MODO_DIA -> DayColorScheme
      AppThemeMode.MODO_NOITE -> NightColorScheme
      AppThemeMode.MODO_NATURAL -> NaturalColorScheme
      AppThemeMode.MODO_CEU -> SkyColorScheme
    }

  val surfaceTokens =
    when (themeMode) {
      AppThemeMode.MODO_DIA -> daySurfaceTokens()
      AppThemeMode.MODO_NOITE -> nightSurfaceTokens()
      AppThemeMode.MODO_NATURAL -> naturalSurfaceTokens()
      AppThemeMode.MODO_CEU -> skySurfaceTokens()
    }

  CompositionLocalProvider(LocalAnoteVoceSurfaceTokens provides surfaceTokens) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = AnoteVoceTypography,
      shapes = AnoteVoceShapes,
      content = content,
    )
  }
}
