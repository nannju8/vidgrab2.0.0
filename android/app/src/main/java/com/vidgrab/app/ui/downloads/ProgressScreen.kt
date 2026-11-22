package com.vidgrab.app.ui.downloads

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.data.BatchStatus
import com.vidgrab.app.data.DlStatus
import com.vidgrab.app.data.DownloadTask
import com.vidgrab.app.data.Format
import com.vidgrab.app.download.BatchSeriesManager
import com.vidgrab.app.download.DownloadCenter
import com.vidgrab.app.ui.components.GlassCard
import com.vidgrab.app.ui.components.ScreenTitle
import com.vidgrab.app.ui.components.ShimmerProgress
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.components.glass
import com.vidgrab.app.ui.components.staggerIn
import com.vidgrab.app.ui.theme.VG
import kotlin.math.sin

@Composable
fun ProgressScreen() {
    val tasks by DownloadCenter.tasks.collectAsStateWithLifecycle()
    val blocked by DownloadCenter.wifiBlocked.collectAsStateWithLifecycle()
    val batch by BatchSeriesManager.state.collectAsStateWithLifecycle()
    val speed: Long = tasks.filter { it.status == DlStatus.RUNNING }.sumOf { it.speedBps }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("PROGRESSION")
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(VG.Cyan.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp), tint = VG.Cyan)
                Spacer(Modifier.width(6.dp))
                Text(Format.speed(speed), color = VG.Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (blocked) {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), glow = VG.Coral) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WifiOff, null, tint = VG.Coral)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Mode « Wi-Fi uniquement » actif : les téléchargements sont en attente. Ils reprendront automatiquement dès la connexion à un réseau Wi-Fi.",
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (batch.status != BatchStatus.IDLE) {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), glow = VG.Cyan) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = VG.Cyan)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Mode Série · Ép. ${batch.currentEp}/${batch.config?.endEp ?: "?"}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(batch.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "Arrêter",
                        color = VG.Coral,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.bounceClick { BatchSeriesManager.stop() }.padding(6.dp)
                    )
                }
            }
        }

        if (tasks.isEmpty()) EmptyState()
        else LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(tasks, key = { _, t -> t.id }) { i, t -> TaskCard(t, Modifier.staggerIn(i)) }
        }
    }
}

@Composable
private fun EmptyState() {
    val t = rememberInfiniteTransition(label = "float")
    val phase by t.animateFloat(0f, 6.2831855f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Column(Modifier.fillMaxSize().padding(bottom = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val dy: Float = sin(phase) * 14f
        Box(
            Modifier.size(150.dp).graphicsLayer {
                translationY = dy
                rotationY = sin(phase) * 10f
                cameraDistance = 16f * density
            }.glass(CircleShape, VG.Indigo, 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(104.dp).background(Brush.linearGradient(listOf(VG.Indigo.copy(alpha = 0.35f), VG.Cyan.copy(alpha = 0.35f))), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.CloudDownload, null, Modifier.size(56.dp), tint = Color.White) }
        }
        // Ombre au sol qui respire avec la lévitation
        Box(
            Modifier.padding(top = 18.dp).width((90f - dy).dp).height(10.dp)
                .background(Brush.radialGradient(listOf(VG.Indigo.copy(alpha = 0.35f), Color.Transparent)), CircleShape)
        )
        Spacer(Modifier.height(22.dp))
        Text("Aucun téléchargement en cours", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "Détectez une vidéo dans l'onglet navigateur pour la voir apparaître ici.",
            Modifier.padding(horizontal = 40.dp, vertical = 6.dp), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
        )
    }
}

@Composable
private fun TaskCard(t: DownloadTask, modifier: Modifier) {
    val running = t.status == DlStatus.RUNNING
    val frac: Float = if (t.total > 0L) (t.downloaded.toDouble() / t.total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    val glow = when (t.status) {
        DlStatus.FAILED -> VG.Coral
        DlStatus.RUNNING, DlStatus.MERGING -> VG.Cyan
        else -> VG.Indigo
    }
    GlassCard(modifier.fillMaxWidth(), glow = glow, tilt = true) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowIcon(running)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "${t.video.format} · ${t.video.resolution}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ShimmerProgress(if (t.status == DlStatus.MERGING) 1f else frac, active = running || t.status == DlStatus.MERGING)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${Format.size(t.downloaded)} / ${Format.size(t.total)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${(frac * 100f).toInt()} %", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VG.Cyan)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val statusText = when (t.status) {
                    DlStatus.RUNNING -> "${Format.speed(t.speedBps)} · reste ${Format.eta(t.etaSec)}"
                    DlStatus.QUEUED -> "En file d'attente"
                    DlStatus.PAUSED -> "En pause"
                    DlStatus.WAITING_WIFI -> "En attente du Wi-Fi"
                    DlStatus.MERGING -> "Fusion et enregistrement…"
                    DlStatus.FAILED -> "Échec : ${t.error ?: "erreur"}"
                    else -> ""
                }
                Text(
                    statusText, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (t.status == DlStatus.FAILED) VG.Coral else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (t.status != DlStatus.MERGING) {
                    when (t.status) {
                        DlStatus.PAUSED, DlStatus.WAITING_WIFI -> RoundAction(Icons.Rounded.PlayArrow, VG.Cyan) { DownloadCenter.resume(t.id) }
                        DlStatus.FAILED -> RoundAction(Icons.Rounded.Refresh, VG.Cyan) { DownloadCenter.resume(t.id) }
                        else -> RoundAction(Icons.Rounded.Pause, VG.Indigo) { DownloadCenter.pause(t.id) }
                    }
                    Spacer(Modifier.width(8.dp))
                    RoundAction(Icons.Rounded.Cancel, VG.Coral) { DownloadCenter.cancel(t.id) }
                }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).shadow(6.dp, CircleShape, ambientColor = tint, spotColor = tint)
            .clip(CircleShape).background(tint.copy(alpha = 0.18f)).bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** Icône de flux animée : la flèche « coule » en boucle pendant le téléchargement. */
@Composable
private fun FlowIcon(active: Boolean) {
    val t = rememberInfiniteTransition(label = "flow")
    val y by t.animateFloat(-5f, 5f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "y")
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(VG.brand),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Downloading, null, tint = Color.White, modifier = Modifier.size(26.dp).graphicsLayer { translationY = if (active) y else 0f })
    }
}
