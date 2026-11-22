package com.vidgrab.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.ui.components.AuroraBackground
import com.vidgrab.app.ui.components.GlassCard
import com.vidgrab.app.ui.components.GlassIconButton
import com.vidgrab.app.ui.components.ScreenTitle
import com.vidgrab.app.ui.components.SectionLabel
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.library.PinDialog
import com.vidgrab.app.ui.library.PinMode
import com.vidgrab.app.ui.theme.VG
import com.vidgrab.app.web.TabManager

private enum class PinTask { NONE, VERIFY_CHANGE, CREATE, VERIFY_REMOVE }

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val wifiOnly by AppPrefs.wifiOnly.collectAsStateWithLifecycle()
    val theme by AppPrefs.themeMode.collectAsStateWithLifecycle()
    var pinTask by remember { mutableStateOf(PinTask.NONE) }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Retour", onClick = onBack)
                Spacer(Modifier.width(8.dp))
                ScreenTitle("PARAMÈTRES")
            }

            SectionLabel("Données")
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth(), glow = VG.Cyan) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Wifi, null, tint = VG.Cyan)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Télécharger uniquement en Wi-Fi", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Met les téléchargements en attente dès le passage en données mobiles.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            AppPrefs.setWifiOnly(it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = VG.Cyan)
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Apparence")
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth(), glow = VG.Indigo) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Palette, null, tint = VG.Indigo)
                        Spacer(Modifier.width(14.dp))
                        Text("Thème", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("dark" to "Sombre", "light" to "Clair", "system" to "Système").forEach { (k, label) ->
                            FilterChip(selected = theme == k, onClick = { AppPrefs.setThemeMode(k) }, label = { Text(label) })
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Dossier privé")
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth(), glow = VG.Magenta) {
                Column {
                    ActionRow(Icons.Rounded.Lock, if (AppPrefs.hasPin()) "Changer le code PIN" else "Définir un code PIN", VG.Magenta) {
                        pinTask = if (AppPrefs.hasPin()) PinTask.VERIFY_CHANGE else PinTask.CREATE
                    }
                    if (AppPrefs.hasPin()) {
                        ActionRow(Icons.Rounded.DeleteSweep, "Supprimer le code PIN", VG.Coral) { pinTask = PinTask.VERIFY_REMOVE }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Confidentialité")
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth(), glow = VG.Coral) {
                Column {
                    ActionRow(Icons.Rounded.DeleteSweep, "Effacer l'historique", VG.Coral) {
                        AppPrefs.clearHistory(); Toast.makeText(ctx, "Historique effacé", Toast.LENGTH_SHORT).show()
                    }
                    ActionRow(Icons.Rounded.Star, "Supprimer tous les favoris", VG.Gold) {
                        AppPrefs.clearBookmarks(); Toast.makeText(ctx, "Favoris supprimés", Toast.LENGTH_SHORT).show()
                    }
                    ActionRow(Icons.Rounded.DeleteSweep, "Effacer cookies et cache web", VG.Coral) {
                        TabManager.clearAll(); Toast.makeText(ctx, "Données web effacées", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            Text(
                "VidGrab 1.0 · com.vidgrab.app",
                Modifier.fillMaxWidth(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    when (pinTask) {
        PinTask.VERIFY_CHANGE -> PinDialog(PinMode.VERIFY, { pinTask = PinTask.CREATE }, { pinTask = PinTask.NONE })
        PinTask.CREATE -> PinDialog(PinMode.CREATE, { AppPrefs.setPin(it); pinTask = PinTask.NONE; Toast.makeText(ctx, "Code PIN enregistré", Toast.LENGTH_SHORT).show() }, { pinTask = PinTask.NONE })
        PinTask.VERIFY_REMOVE -> PinDialog(PinMode.VERIFY, { AppPrefs.clearPin(); pinTask = PinTask.NONE; Toast.makeText(ctx, "Code PIN supprimé", Toast.LENGTH_SHORT).show() }, { pinTask = PinTask.NONE })
        PinTask.NONE -> Unit
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().bounceClick(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontWeight = FontWeight.Medium)
    }
}
