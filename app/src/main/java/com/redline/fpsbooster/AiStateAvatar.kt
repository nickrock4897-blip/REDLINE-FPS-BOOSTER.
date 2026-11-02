package com.redline.fpsbooster

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AiStateAvatar(state: String, frames: Long, analyzed: Long, changes: Long) {
    val activeMotion = state in setOf("Idle", "Starting", "Active", "Watching Screen", "Analyzing", "Reacting", "Thinking", "Speaking")
    val targetColor = when (state) {
        "Starting" -> Color(0xFFDF42F5)
        "Active", "Watching Screen" -> Color(0xFF70F0B4)
        "Analyzing", "Thinking" -> Color(0xFF58C7FF)
        "Reacting", "Speaking" -> Color(0xFFFFC66D)
        "Paused" -> Color(0xFFFFA45B)
        "Error" -> Color(0xFFFF405E)
        "Stopped" -> Color(0xFF8A829B)
        else -> Color(0xFFDF42F5)
    }
    val color by animateColorAsState(targetColor, animationSpec = tween(350), label = "avatarColor")
    val transition = rememberInfiniteTransition(label = "redlineAiMotion")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(if (state == "Analyzing") 420 else 1050), RepeatMode.Reverse),
        label = "avatarPulse"
    )
    val rotation by transition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(if (state == "Reacting") 320 else 1700), RepeatMode.Reverse),
        label = "avatarMotion"
    )
    val scale by animateFloatAsState(if (activeMotion) pulse else 1f, tween(250), label = "avatarScale")
    val label = when (state) {
        "Idle" -> "IDLE"
        "Starting" -> "STARTING AI"
        "Active" -> "AI ACTIVE"
        "Watching Screen" -> "WATCHING SCREEN"
        "Analyzing" -> "ANALYZING FRAME"
        "Reacting" -> "SCREEN CHANGE DETECTED"
        "Thinking" -> "THINKING"
        "Speaking" -> "RESPONDING"
        "Paused" -> "PAUSED"
        "Error" -> "ERROR"
        else -> "STOPPED"
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(76.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = if (activeMotion) rotation else 0f
                }
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f), Color(0xFF191427))))
                .border(1.dp, color.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), color.copy(alpha = 0.85f), color.copy(alpha = 0.25f)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, contentDescription = "REDLINE AI Status", tint = Color(0xFF190F25), modifier = Modifier.size(27.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text("Frames: $frames  •  analysiert: $analyzed", color = Color(0xFFB8B1C9), fontSize = 10.sp)
            Text("Bildschirmänderungen: $changes", color = Color(0xFFB8B1C9), fontSize = 10.sp)
        }
    }
}
