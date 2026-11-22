package com.vidgrab.app.ui.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.theme.VG

/**
 * FAB 3D de détection : sphère grise inactive → rotationY 360° (spring bouncy), dégradé Corail/Magenta,
 * ondes radar concentriques pulsantes et badge compteur en relief quand des flux sont détectés.
 */
@Composable
fun DetectFab(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = count > 0
    val density = LocalDensity.current.density
    val rot = remember { Animatable(0f) }
    LaunchedEffect(count) {
        if (count > 0) {
            rot.snapTo(0f)
            rot.animateTo(360f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessVeryLow))
        }
    }
    val t = rememberInfiniteTransition(label = "radar")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "phase")

    Box(modifier.size(132.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.size(132.dp)) {
                for (k in 0 until 3) {
                    val ph: Float = (phase + k / 3f) % 1f
                    val r: Float = 32.dp.toPx() + (34.dp.toPx() * ph)
                    drawCircle(VG.Coral.copy(alpha = (1f - ph) * 0.55f), radius = r, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
        Box(
            Modifier.size(64.dp)
                .graphicsLayer {
                    rotationY = rot.value
                    cameraDistance = 16f * density
                }
                .shadow(
                    if (active) 18.dp else 8.dp, CircleShape,
                    ambientColor = if (active) VG.Coral else Color.Black, spotColor = if (active) VG.Magenta else Color.Black
                )
                .clip(CircleShape)
                .background(if (active) VG.alert else androidx.compose.ui.graphics.SolidColor(Color(0x55808898)))
                .border(1.dp, Color.White.copy(alpha = if (active) 0.5f else 0.28f), CircleShape)
                .bounceClick(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Download, "Vidéos détectées", tint = Color.White, modifier = Modifier.size(28.dp))
        }
        if (active) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 14.dp).size(26.dp)
                    .shadow(8.dp, CircleShape, ambientColor = VG.Magenta, spotColor = VG.Coral)
                    .background(Color.White, CircleShape)
                    .border(2.dp, VG.Coral, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(count.toString(), color = VG.Coral, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
