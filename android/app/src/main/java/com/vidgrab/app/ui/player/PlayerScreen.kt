package com.vidgrab.app.ui.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vidgrab.app.data.Format
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.theme.VG
import kotlinx.coroutines.delay

private data class Ripple(val right: Boolean, val token: Long)

/** Lecteur immersif : contrôles translucides, double-tap ±10 s avec onde circulaire, barre tactile, verrouillage. */
@Composable
fun PlayerScreen(player: ExoPlayer, title: String, onBack: () -> Unit, onToggleOrientation: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    var ripple by remember { mutableStateOf<Ripple?>(null) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!seeking) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = if (player.duration > 0L) player.duration else 0L
            }
            delay(300L)
        }
    }
    LaunchedEffect(controls, playing, seeking) {
        if (controls && playing && !seeking) {
            delay(3500L)
            controls = false
        }
    }
    LaunchedEffect(ripple) {
        if (ripple != null) {
            delay(650L)
            ripple = null
        }
    }

    fun seekBy(deltaMs: Long) {
        val target: Long = (player.currentPosition + deltaMs).coerceIn(0L, if (player.duration > 0L) player.duration else Long.MAX_VALUE)
        player.seekTo(target)
        position = target
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Couche de gestes : tap = afficher/masquer, double-tap gauche/droite = ∓10 s
        Box(
            Modifier.fillMaxSize().pointerInput(locked) {
                detectTapGestures(
                    onTap = { controls = !controls },
                    onDoubleTap = { offset ->
                        if (!locked) {
                            val right = offset.x > size.width / 2f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            seekBy(if (right) 10_000L else -10_000L)
                            ripple = Ripple(right, System.nanoTime())
                        }
                    }
                )
            }
        )

        ripple?.let { r -> SeekRipple(r) }

        // Verrouillage : seule l'icône de cadenas reste disponible
        AnimatedVisibility(visible = controls && locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
            RoundButton(Icons.Rounded.Lock, 56.dp, VG.alert) { locked = false }
        }

        AnimatedVisibility(visible = controls && !locked, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.28f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.38f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))

                Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, 44.dp, null, onBack)
                    Spacer(Modifier.width(12.dp))
                    Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }

                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.Rounded.Replay10, 56.dp, null) { seekBy(-10_000L) }
                    RoundButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 76.dp, VG.brand) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    RoundButton(Icons.Rounded.Forward10, 56.dp, null) { seekBy(10_000L) }
                }

                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Slider(
                        value = if (seeking) seekFraction else if (duration > 0L) position.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { seeking = true; seekFraction = it },
                        onValueChangeFinished = {
                            if (duration > 0L) player.seekTo((seekFraction * duration.toFloat()).toLong())
                            seeking = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = VG.Cyan, activeTrackColor = VG.Indigo, inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        )
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val shown: Long = if (seeking) (seekFraction * duration.toFloat()).toLong() else position
                        Text("${Format.duration(shown)} / ${Format.duration(duration)}", color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        RoundButton(Icons.Rounded.LockOpen, 40.dp, null) { locked = true; controls = true }
                        Spacer(Modifier.width(10.dp))
                        RoundButton(if (landscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, 40.dp, null, onToggleOrientation)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, size: androidx.compose.ui.unit.Dp, brush: Brush?, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(brush ?: Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.10f))))
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

/** Onde circulaire au double-tap. */
@Composable
private fun SeekRipple(r: Ripple) {
    val p = remember(r.token) { Animatable(0f) }
    LaunchedEffect(r.token) { p.animateTo(1f, androidx.compose.animation.core.tween(600)) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(if (r.right) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight(0.7f).width(180.dp)
                .graphicsLayer {
                    scaleX = 0.6f + p.value * 0.8f
                    scaleY = 0.6f + p.value * 0.8f
                    alpha = (1f - p.value).coerceIn(0f, 1f)
                }
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (r.right) Icons.Rounded.Forward10 else Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text(if (r.right) "+10 s" else "-10 s", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
