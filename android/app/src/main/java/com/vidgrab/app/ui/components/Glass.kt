package com.vidgrab.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.ui.theme.LocalDarkTheme
import com.vidgrab.app.ui.theme.VG
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bordure lumineuse ultra-fine : blanc 15 % → transparent. */
@Composable
fun glassBorderBrush(): Brush {
    val base = if (LocalDarkTheme.current) Color.White else Color.Black
    return Brush.linearGradient(listOf(base.copy(alpha = if (LocalDarkTheme.current) 0.15f else 0.10f), Color.Transparent))
}

/** Surface glassmorphism : surface à 70 %, bordure en dégradé 1 dp, ombre portée colorée. */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(20.dp),
    glow: Color = VG.Indigo,
    elevation: Dp = 12.dp,
    alpha: Float = 0.7f
): Modifier = this
    .shadow(elevation, shape, ambientColor = glow.copy(alpha = 0.35f), spotColor = glow.copy(alpha = 0.45f))
    .clip(shape)
    .background(MaterialTheme.colorScheme.surface.copy(alpha = alpha))
    .border(1.dp, glassBorderBrush(), shape)

/**
 * Tilt 3D : rotationX / rotationY pilotés par le point de contact (spring), cameraDistance = 16f * density,
 * et reflet spéculaire qui glisse sous le doigt. N'interfère pas avec les clics (passe Initial, rien n'est consommé).
 */
@Composable
fun Modifier.tilt3D(shape: Shape, maxDeg: Float = 7f): Modifier {
    val density = LocalDensity.current.density
    val rx = remember { Animatable(0f) }
    val ry = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }
    val touch = remember { mutableStateOf(Offset.Unspecified) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val soft = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    val bouncy = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

    return this
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                scope.launch { press.animateTo(0.97f, soft) }
                fun apply(p: Offset) {
                    val w = size.width.coerceAtLeast(1).toFloat()
                    val h = size.height.coerceAtLeast(1).toFloat()
                    val nx: Float = ((p.x / w) - 0.5f) * 2f
                    val ny: Float = ((p.y / h) - 0.5f) * 2f
                    touch.value = p
                    scope.launch { ry.animateTo((nx * maxDeg).coerceIn(-maxDeg, maxDeg), soft) }
                    scope.launch { rx.animateTo((-ny * maxDeg).coerceIn(-maxDeg, maxDeg), soft) }
                }
                apply(down.position)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val c = event.changes.firstOrNull()
                    if (c != null && c.pressed) apply(c.position)
                } while (event.changes.any { it.pressed })
                touch.value = Offset.Unspecified
                scope.launch { rx.animateTo(0f, bouncy) }
                scope.launch { ry.animateTo(0f, bouncy) }
                scope.launch { press.animateTo(1f, bouncy) }
            }
        }
        .graphicsLayer {
            rotationX = rx.value
            rotationY = ry.value
            scaleX = press.value
            scaleY = press.value
            cameraDistance = 16f * density
        }
        .drawWithContent {
            drawContent()
            val p = touch.value
            if (p.isSpecified) {
                val outline = shape.createOutline(this.size, layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                clipPath(path) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                            center = p, radius = this.size.width * 0.6f
                        )
                    )
                }
            }
        }
}

/** Carte glass avec tilt 3D optionnel, clic et appui long (retour haptique). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    glow: Color = VG.Indigo,
    tilt: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var m = modifier
    if (tilt) m = m.tilt3D(shape)
    m = m.glass(shape, glow)
    if (onClick != null || onLongClick != null) {
        m = m.combinedClickable(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick?.invoke()
            },
            onLongClick = if (onLongClick != null) {
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            } else null
        )
    }
    Box(m, content = content)
}

/** Effet d'enfoncement 3D au toucher (scale + rotationX en spring) avec haptique. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bounceClick(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val density = LocalDensity.current.density
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press-scale"
    )
    val rot by animateFloatAsState(
        if (pressed) 14f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press-rot"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            rotationX = rot
            cameraDistance = 16f * density
        }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onLongClick = if (onLongClick != null) {
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            } else null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        )
}

/** Apparition en cascade : décalage Z (scale) + translation + fondu. */
@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(10) * 60L)
        p.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
    }
    return this.graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        val s = 0.88f + 0.12f * p.value
        scaleX = s
        scaleY = s
        translationY = (1f - p.value) * 48f
    }
}

/** Fond vivant : dégradé obsidienne + halos d'aurore flous animés lentement. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = LocalDarkTheme.current
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val bg = if (dark) listOf(VG.BgTop, VG.BgBottom) else listOf(Color(0xFFF4F6FF), Color(0xFFE9ECFB))
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(bg))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val alpha = if (dark) 0.34f else 0.20f
            fun halo(c: Color, cx: Float, cy: Float, r: Float, k: Float) {
                drawCircle(
                    Brush.radialGradient(listOf(c.copy(alpha = alpha * k), Color.Transparent), center = Offset(cx, cy), radius = r),
                    radius = r, center = Offset(cx, cy)
                )
            }
            halo(VG.Indigo, w * (0.15f + 0.45f * a), h * (0.08f + 0.05f * b), w * 0.75f, 1f)
            halo(VG.Cyan, w * (0.95f - 0.45f * b), h * (0.04f + 0.08f * a), w * 0.6f, 0.9f)
            halo(VG.Magenta, w * (0.45f + 0.3f * a), h * (0.5f + 0.1f * b), w * 0.6f, 0.45f)
        }
        content()
    }
}

/** Barre de progression fluide avec reflet lumineux (shimmer). */
@Composable
fun ShimmerProgress(progress: Float, modifier: Modifier = Modifier, active: Boolean = true) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    val animated by animateFloatAsState(progress.coerceIn(0.001f, 1f), tween(500), label = "p")
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .background(VG.progressBrush)
                .drawWithContent {
                    drawContent()
                    if (active) {
                        val cx = size.width * x
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                startX = cx - 90f, endX = cx + 90f
                            )
                        )
                    }
                }
        )
    }
}

/** Bouton pilule dégradé. */
@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    brush: Brush = VG.brand,
    glow: Color = VG.Indigo,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .shadow(14.dp, shape, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .background(brush)
            .bounceClick(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Bouton carré arrondi en relief glassmorphism. */
@Composable
fun GlassSquareButton(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    glow: Color = VG.Indigo,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier
            .size(size)
            .glass(RoundedCornerShape(14.dp), glow, elevation = 8.dp)
            .bounceClick(onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun GlassIconButton(icon: ImageVector, desc: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Box(
        modifier.size(40.dp).clip(CircleShape).bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Black, letterSpacing = 3.sp, brush = VG.brand
        )
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
    )
}