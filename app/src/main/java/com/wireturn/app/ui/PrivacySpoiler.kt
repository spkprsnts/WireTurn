@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun String.redact(enabled: Boolean): String {
    if (!enabled) return this
    return " ".repeat(this.length.coerceAtLeast(12))
}

/**
 * Telegram-style spoiler effect with magic sparks.
 */
@Composable
fun Modifier.privacySpoiler(
    enabled: Boolean,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    sparkColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
): Modifier {
    if (!enabled) return this

    val infiniteTransition = rememberInfiniteTransition(label = "privacySpoiler")
    val t by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    return this.drawWithContent {
        if (enabled) {
            drawRect(color = containerColor)
            
            // Стабильный seed на основе размера, чтобы не было мерцания при микро-изменениях size
            val seed = (size.width.toInt() shl 16) xor size.height.toInt()
            val random = Random(seed.toLong() + 42L)
            
            // Оптимизированная плотность: 1 искра на ~40 пикселей (по площади)
            // Этого достаточно для создания густого эффекта, но гораздо легче для GPU
            val count = (size.width * size.height / 40).toInt().coerceIn(60, 500)
            
            clipRect {
                repeat(count) {
                    val x = random.nextFloat() * size.width
                    val y = random.nextFloat() * size.height
                    
                    val individualPhase = random.nextFloat()
                    // Используем только целые множители скорости (1 или 2), 
                    // чтобы анимация была идеально бесшовной при t=0 и t=1
                    val speedScale = if (random.nextBoolean()) 1f else 2f
                    val particleT = (t * speedScale + individualPhase) % 1f
                    
                    // Плавное мерцание (синус от 0 до PI всегда возвращается в 0)
                    val alphaScale = kotlin.math.sin(particleT * Math.PI).toFloat()
                    
                    val radiusX = 1.5.dp.toPx() + random.nextFloat() * 2.5.dp.toPx()
                    val radiusY = 1.5.dp.toPx() + random.nextFloat() * 2.5.dp.toPx()
                    
                    // Рандомизируем направление вращения и тип траектории
                    val dirX = if (random.nextBoolean()) 1f else -1f
                    val dirY = if (random.nextBoolean()) 1f else -1f
                    val movementType = random.nextInt(3)
                    
                    val (offsetX, offsetY) = when (movementType) {
                        0 -> { // Эллипс
                            kotlin.math.sin(particleT * 2 * Math.PI).toFloat() * radiusX * dirX to
                            kotlin.math.cos(particleT * 2 * Math.PI).toFloat() * radiusY * dirY
                        }
                        1 -> { // Восьмерка
                            kotlin.math.sin(particleT * 2 * Math.PI).toFloat() * radiusX * dirX to
                            kotlin.math.sin(particleT * 4 * Math.PI).toFloat() * radiusY * dirY
                        }
                        else -> { // Колебание по диагонали
                            val angle = individualPhase * 2 * Math.PI
                            val dist = kotlin.math.sin(particleT * 2 * Math.PI).toFloat() * radiusX
                            kotlin.math.cos(angle).toFloat() * dist * dirX to kotlin.math.sin(angle).toFloat() * dist * dirY
                        }
                    }
                    
                    drawCircle(
                        color = sparkColor.copy(alpha = alphaScale * sparkColor.alpha),
                        radius = (random.nextFloat() * 1.2.dp.toPx()).coerceAtLeast(0.7.dp.toPx()),
                        center = Offset(x + offsetX, y + offsetY)
                    )
                }
            }
        } else {
            drawContent()
        }
    }
}
