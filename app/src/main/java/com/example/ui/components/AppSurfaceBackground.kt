package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens

@Composable
fun AppSurfaceBackground(
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val backgroundColor = MaterialTheme.colorScheme.background
  val surfaceTokens = LocalAnoteVoceSurfaceTokens.current

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(backgroundColor)
        .drawBehind {
          drawRect(
            brush =
              Brush.verticalGradient(
                colors =
                  listOf(
                    surfaceTokens.atmosphereTint.copy(alpha = 0.28f),
                    backgroundColor.copy(alpha = 0f),
                  ),
                startY = 0f,
                endY = size.height * 0.42f,
              )
          )
          val spacing = 40.dp.toPx()
          val strokeWidth = 0.8.dp.toPx()
          var y = spacing
          while (y < size.height) {
            drawLine(
              color = surfaceTokens.subtleRuleLine.copy(alpha = 0.35f),
              start = Offset(0f, y),
              end = Offset(size.width, y),
              strokeWidth = strokeWidth,
            )
            y += spacing
          }
        },
    content = content,
  )
}
