package com.echolicense.vpn.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.echolicense.vpn.data.ConnectionState

val GlassBorderTop = Color(0x66FFFFFF)
val GlassBorderBottom = Color(0x14FFFFFF)
val EmeraldGlow = Color(0xFF00E5A3)
val CyanLiquid = Color(0xFF38BDF8)
val AmberWarn = Color(0xFFFFB020)
val DangerRed = Color(0xFFFF4D6D)
val GamingRed = Color(0xFFFF5A6E)

@Composable
fun LiquidAmbientBackground(
    state: ConnectionState,
    content: @Composable BoxScope.() -> Unit
) {
    val topOrb by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> Color(0xFF0A4F3C)
            ConnectionState.CONNECTING -> Color(0xFF0E4A6B)
            ConnectionState.ERROR -> Color(0xFF5A1626)
            ConnectionState.DISCONNECTED -> Color(0xFF1A1F3C)
        },
        animationSpec = tween(700),
        label = "topOrb"
    )
    val bottomOrb by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> Color(0xFF04261E)
            ConnectionState.CONNECTING -> Color(0xFF09223B)
            else -> Color(0xFF0B0E1A)
        },
        animationSpec = tween(700),
        label = "bottomOrb"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(topOrb, Color(0xFF090D16), bottomOrb),
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 2200f)
                )
            ),
        content = content
    )
}

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    highlightColor: Color = GlassBorderTop,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0x29FFFFFF), Color(0x12FFFFFF)),
                    start = Offset(0f, 0f),
                    end = Offset(400f, 600f)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(highlightColor, GlassBorderBottom),
                    start = Offset(0f, 0f),
                    end = Offset(300f, 500f)
                ),
                shape = shape
            ),
        content = content
    )
}

fun Modifier.bounceClick(
    scaleDown: Float = 0.94f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bounceScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}
