package com.vidgrab.app.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

private fun isBatteryUnrestricted(ctx: Context): Boolean {
    val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

@SuppressLint("BatteryLife")
private fun openBatterySettings(ctx: Context, alreadyUnrestricted: Boolean) {
    if (!alreadyUnrestricted) {
        val req = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }
        if (runCatching { ctx.startActivity(req) }.isSuccess) return
    }
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${ctx.packageName}")
    }
    runCatching { ctx.startActivity(details) }.onFailure {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val wifiOnly by AppPrefs.wifiOnly.collectAsStateWithLifecycle()
    val theme by AppPrefs.themeMode.collectAsStateWithLifecycle()
    var pinTask by remember { mutableStateOf(PinTask.NONE) }
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(ctx)) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryUnrestricted = isBatteryUnrestricted(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    AuroraBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Retour", onClick = onBack)
                Spacer(Modifier.width(8.dp))
                ScreenTitle("PARAMÈTRES")
            }

            SectionLabel("Données & Vitesse")
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth(), glow = VG.Cyan) {
                Column {
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

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .bounceClick { openBatterySettings(ctx, batteryUnrestricted) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (batteryUnrestricted) Icons.Rounded.CheckCircle else Icons.Rounded.Bolt,
                            null,
                            tint = if (batteryUnrestricted) VG.Cyan else VG.Gold
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Batterie en arrière-plan", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (batteryUnrestricted) "Sans restriction : le débit Wi-Fi reste stable écran éteint."
                                else "Touchez pour autoriser « Sans restriction » et éviter le bridage écran éteint.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (batteryUnrestricted) "OK" else "Activer",
                            color = if (batteryUnrestricted) VG.Cyan else VG.Gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
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

            Spacer(Modifier.height(22.dp))
            SectionLabel("À propos de l'application")
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth(), glow = VG.Indigo) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = VG.Cyan)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("VidGrab · Application IA", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Version 1.0.0 · com.vidgrab.app", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "VidGrab est une application intelligente assistée par IA conçue pour automatiser l'analyse des pages web, détecter les flux vidéo (MP4, HLS), télécharger à haute vitesse (y compris des séries complètes) et partager vos vidéos hors ligne en Wi-Fi, tout en protégeant votre vie privée.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Person, null, tint = VG.Indigo, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Créateur : Juliano Nantenaina", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VG.Cyan)
                    }
                }
            }
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
private fun ActionRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().bounceClick(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontWeight = FontWeight.Medium)
    }
}
