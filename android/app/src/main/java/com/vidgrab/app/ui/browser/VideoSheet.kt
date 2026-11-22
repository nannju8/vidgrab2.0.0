package com.vidgrab.app.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.data.Format
import com.vidgrab.app.download.DownloadCenter
import com.vidgrab.app.ui.components.GlassCard
import com.vidgrab.app.ui.components.GradientButton
import com.vidgrab.app.ui.components.staggerIn
import com.vidgrab.app.ui.theme.VG
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSheet(videos: List<DetectedVideo>, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        scrimColor = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text(
                "Vidéos détectées (${videos.size})",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, brush = VG.alert)
            )
            Spacer(Modifier.size(12.dp))
            if (videos.isEmpty()) {
                Text(
                    "Aucun flux pour l'instant. Lancez la lecture d'une vidéo sur la page : elle apparaîtra ici automatiquement.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                        GlassCard(Modifier.fillMaxWidth().staggerIn(i), glow = VG.Coral, tilt = true) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(VG.alert),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Rounded.Movie, null, tint = Color.White) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Spacer(Modifier.size(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Badge(v.format, VG.Indigo)
                                            Badge(v.resolution, VG.Cyan)
                                            Text(Format.size(v.sizeBytes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Spacer(Modifier.size(12.dp))
                                GradientButton(
                                    "Télécharger", Modifier.fillMaxWidth(), Icons.Rounded.Download,
                                    brush = VG.alert, glow = VG.Coral,
                                    onClick = {
                                        DownloadCenter.enqueue(v)
                                        scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 3.dp)
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}
