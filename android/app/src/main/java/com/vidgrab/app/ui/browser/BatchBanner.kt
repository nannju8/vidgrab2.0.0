package com.vidgrab.app.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.data.BatchStatus
import com.vidgrab.app.download.BatchSeriesManager
import com.vidgrab.app.ui.components.GlassCard
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.theme.VG

@Composable
fun BatchBanner(tabId: Int) {
    val st by BatchSeriesManager.state.collectAsStateWithLifecycle()
    val visible = st.config?.tabId == tabId && st.status != BatchStatus.IDLE

    AnimatedVisibility(visible = visible) {
        val alert = st.status == BatchStatus.NEEDS_USER
        val glow = if (alert) VG.Coral else VG.Cyan
        GlassCard(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            shape = RoundedCornerShape(18.dp),
            glow = glow
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (alert) Icons.Rounded.Warning else Icons.Rounded.AutoAwesome,
                    null,
                    tint = glow,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val cfg = st.config
                    Text(
                        if (cfg != null) "Série · Ép. ${st.currentEp}/${cfg.endEp} (${st.completedCount} ajouté(s))" else "Série",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = glow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        st.message,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (st.status == BatchStatus.PAUSED || st.status == BatchStatus.NEEDS_USER) {
                    MiniAction(Icons.Rounded.PlayArrow, VG.Cyan) { BatchSeriesManager.resume() }
                } else if (st.status != BatchStatus.DONE) {
                    MiniAction(Icons.Rounded.Pause, VG.Indigo) { BatchSeriesManager.pause() }
                }
                if (st.status != BatchStatus.DONE) {
                    Spacer(Modifier.width(6.dp))
                    MiniAction(Icons.Rounded.SkipNext, VG.Gold) { BatchSeriesManager.skipCurrent() }
                }
                Spacer(Modifier.width(6.dp))
                MiniAction(Icons.Rounded.Close, VG.Coral) { BatchSeriesManager.stop() }
            }
        }
    }
}

@Composable
private fun MiniAction(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)).bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
    }
}
