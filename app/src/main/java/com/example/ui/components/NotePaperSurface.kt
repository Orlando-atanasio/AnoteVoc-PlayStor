package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.model.NotePaperStyle
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens

@Immutable
data class NotePaperPalette(
  val containerColor: Color,
  val secondaryTintColor: Color,
  val contentColor: Color,
  val secondaryContentColor: Color,
  val ruleColor: Color,
  val accentColor: Color,
  val borderColor: Color,
  val isDarkPaper: Boolean,
)

@Composable
fun resolveNotePaperPalette(paperStyle: NotePaperStyle): NotePaperPalette {
  val themeTokens = LocalAnoteVoceSurfaceTokens.current
  val scheme = MaterialTheme.colorScheme
  val appIsDark = themeTokens.isDark

  return when (paperStyle) {
    NotePaperStyle.LINHAS,
    NotePaperStyle.SEM_LINHAS,
    NotePaperStyle.PONTILHADO ->
      NotePaperPalette(
        containerColor = scheme.surface,
        secondaryTintColor = scheme.surface,
        contentColor = scheme.onSurface,
        secondaryContentColor = scheme.onSurfaceVariant,
        ruleColor =
          if (appIsDark) {
            scheme.outline.copy(alpha = 0.35f)
          } else {
            scheme.outlineVariant.copy(alpha = 0.78f)
          },
        accentColor = scheme.primary,
        borderColor = themeTokens.cardBorder,
        isDarkPaper = appIsDark,
      )

    NotePaperStyle.ELEGANCE_NOIR ->
      NotePaperPalette(
        containerColor = Color(0xFF171618),
        secondaryTintColor = Color(0xFF211E20),
        contentColor = Color(0xFFF3EFE6),
        secondaryContentColor = Color(0xFFC5BDAE),
        ruleColor = Color(0xFFD4AF37).copy(alpha = 0.22f),
        accentColor = Color(0xFFE0C068),
        borderColor = Color(0xFF3E3830),
        isDarkPaper = true,
      )

    NotePaperStyle.OCEAN_BREEZE ->
      NotePaperPalette(
        containerColor = Color(0xFFF0F8FA),
        secondaryTintColor = Color(0xFFE0F1F6),
        contentColor = Color(0xFF102A36),
        secondaryContentColor = Color(0xFF3B5A68),
        ruleColor = Color(0xFF7BB8C9).copy(alpha = 0.34f),
        accentColor = Color(0xFF0E6D82),
        borderColor = Color(0xFFBBDCE5),
        isDarkPaper = false,
      )

    NotePaperStyle.SUNSET_WARMTH ->
      NotePaperPalette(
        containerColor = Color(0xFFFFF6EC),
        secondaryTintColor = Color(0xFFFEEADB),
        contentColor = Color(0xFF331D12),
        secondaryContentColor = Color(0xFF6B4735),
        ruleColor = Color(0xFFE09F6B).copy(alpha = 0.34f),
        accentColor = Color(0xFFB54D1C),
        borderColor = Color(0xFFF0CFBA),
        isDarkPaper = false,
      )

    NotePaperStyle.FOREST_MIST ->
      NotePaperPalette(
        containerColor = Color(0xFFF2F7F3),
        secondaryTintColor = Color(0xFFE3EFE6),
        contentColor = Color(0xFF15291D),
        secondaryContentColor = Color(0xFF415B4B),
        ruleColor = Color(0xFF8BB59A).copy(alpha = 0.36f),
        accentColor = Color(0xFF2B6143),
        borderColor = Color(0xFFC3DAC9),
        isDarkPaper = false,
      )

    NotePaperStyle.MINIMALIST_PRO ->
      NotePaperPalette(
        containerColor = Color(0xFFFAFAFC),
        secondaryTintColor = Color(0xFFF1F2F6),
        contentColor = Color(0xFF181A20),
        secondaryContentColor = Color(0xFF545966),
        ruleColor = Color(0xFFC9CDD8).copy(alpha = 0.45f),
        accentColor = Color(0xFF364156),
        borderColor = Color(0xFFDADCE4),
        isDarkPaper = false,
      )

    NotePaperStyle.TECH_DARK ->
      NotePaperPalette(
        containerColor = Color(0xFF0E1520),
        secondaryTintColor = Color(0xFF152132),
        contentColor = Color(0xFFE5EFFA),
        secondaryContentColor = Color(0xFF9CB4CC),
        ruleColor = Color(0xFF38BDF8).copy(alpha = 0.16f),
        accentColor = Color(0xFF5CC8FA),
        borderColor = Color(0xFF24364E),
        isDarkPaper = true,
      )
  }
}

@Composable
fun NotePaperSurface(
  paperStyle: NotePaperStyle,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val palette = resolveNotePaperPalette(paperStyle)

  Box(
    modifier =
      modifier.drawBehind {
        val bgBrush =
          Brush.verticalGradient(
            colors = listOf(palette.containerColor, palette.secondaryTintColor)
          )
        drawRect(brush = bgBrush)

        when (paperStyle) {
          NotePaperStyle.LINHAS -> {
            val stepPx = 32.dp.toPx()
            val strokePx = 1.dp.toPx()
            val marginX = 24.dp.toPx()
            drawLine(
              color = palette.accentColor.copy(alpha = 0.22f),
              start = Offset(marginX, 0f),
              end = Offset(marginX, size.height),
              strokeWidth = strokePx,
            )
            var y = stepPx
            while (y < size.height) {
              drawLine(
                color = palette.ruleColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokePx,
              )
              y += stepPx
            }
          }

          NotePaperStyle.PONTILHADO -> {
            val stepPx = 22.dp.toPx()
            val radiusPx = 1.3.dp.toPx()
            var y = stepPx
            while (y < size.height) {
              var x = stepPx
              while (x < size.width) {
                drawCircle(
                  color = palette.ruleColor,
                  radius = radiusPx,
                  center = Offset(x, y),
                )
                x += stepPx
              }
              y += stepPx
            }
          }

          NotePaperStyle.TECH_DARK -> {
            val stepPx = 28.dp.toPx()
            val strokePx = 0.8.dp.toPx()
            var x = stepPx
            while (x < size.width) {
              drawLine(
                color = palette.ruleColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = strokePx,
              )
              x += stepPx
            }
            var y = stepPx
            while (y < size.height) {
              drawLine(
                color = palette.ruleColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokePx,
              )
              y += stepPx
            }
          }

          NotePaperStyle.ELEGANCE_NOIR -> {
            val inset = 12.dp.toPx()
            drawLine(
              color = palette.ruleColor,
              start = Offset(inset, 0f),
              end = Offset(inset, size.height),
              strokeWidth = 1.5.dp.toPx(),
            )
          }

          NotePaperStyle.OCEAN_BREEZE,
          NotePaperStyle.SUNSET_WARMTH,
          NotePaperStyle.FOREST_MIST -> {
            val topRuleY = 6.dp.toPx()
            drawLine(
              color = palette.accentColor.copy(alpha = 0.25f),
              start = Offset(0f, topRuleY),
              end = Offset(size.width, topRuleY),
              strokeWidth = 2.dp.toPx(),
            )
          }

          NotePaperStyle.SEM_LINHAS,
          NotePaperStyle.MINIMALIST_PRO -> Unit
        }
      }
  ) {
    content()
  }
}

@Composable
fun NotePaperSwatch(
  paperStyle: NotePaperStyle,
  modifier: Modifier = Modifier,
) {
  val palette = resolveNotePaperPalette(paperStyle)
  Surface(
    shape = MaterialTheme.shapes.small,
    color = palette.containerColor,
    border = BorderStroke(1.dp, palette.borderColor),
    modifier = modifier.size(32.dp),
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      when (paperStyle) {
        NotePaperStyle.LINHAS -> {
          listOf(0.35f, 0.6f, 0.85f).forEach { fraction ->
            drawLine(
              color = palette.ruleColor,
              start = Offset(4f, size.height * fraction),
              end = Offset(size.width - 4f, size.height * fraction),
              strokeWidth = 1.dp.toPx(),
            )
          }
        }
        NotePaperStyle.PONTILHADO -> {
          listOf(0.3f, 0.7f).forEach { fx ->
            listOf(0.3f, 0.7f).forEach { fy ->
              drawCircle(
                color = palette.accentColor.copy(alpha = 0.7f),
                radius = 1.6.dp.toPx(),
                center = Offset(size.width * fx, size.height * fy),
              )
            }
          }
        }
        NotePaperStyle.SEM_LINHAS -> Unit
        else -> {
          drawLine(
            color = palette.accentColor,
            start = Offset(0f, size.height * 0.25f),
            end = Offset(size.width, size.height * 0.25f),
            strokeWidth = 2.dp.toPx(),
          )
        }
      }
    }
  }
}
