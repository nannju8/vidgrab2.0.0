package com.vidgrab.app.ui.transfer

import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.PlayerActivity
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.data.Format
import com.vidgrab.app.data.LibraryItem
import com.vidgrab.app.transfer.LocalNetHelper
import com.vidgrab.app.transfer.PeerInfo
import com.vidgrab.app.transfer.TransferFileState
import com.vidgrab.app.transfer.TransferManager
import com.vidgrab.app.transfer.TransferRole
import com.vidgrab.app.transfer.TransferState
import com.vidgrab.app.transfer.TransferStatus
import com.vidgrab.app.ui.MainViewModel
import com.vidgrab.app.ui.components.AuroraBackground
import com.vidgrab.app.ui.components.GlassCard
import com.vidgrab.app.ui.components.GlassIconButton
import com.vidgrab.app.ui.components.GradientButton
import com.vidgrab.app.ui.components.ScreenTitle
import com.vidgrab.app.ui.components.ShimmerProgress
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.components.glass
import com.vidgrab.app.ui.library.PinDialog
import com.vidgrab.app.ui.library.PinMode
import com.vidgrab.app.ui.theme.VG

@Composable
fun TransferScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by TransferManager.state.collectAsStateWithLifecycle()
    val pub by vm.library.collectAsStateWithLifecycle()
    val prv by vm.privateItems.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(if (st.role == TransferRole.SENDING) 0 else 1) }
    var pinPeer by remember { mutableStateOf<PeerInfo?>(null) }
    var manualDlg by remember { mutableStateOf(false) }
    var privateUnlocked by remember { mutableStateOf(false) }
    var showPrivateInSend by remember { mutableStateOf(false) }
    var showPrivatePinDlg by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshLibrary()
        if (TransferManager.state.value.role == TransferRole.IDLE) {
            TransferManager.startReceivingMode()
        }
    }
    LaunchedEffect(st.role) {
        if (st.role == TransferRole.SENDING) tab = 0
        else if (st.role == TransferRole.RECEIVING) tab = 1
    }
    DisposableEffect(Unit) {
        onDispose {
            // Ferme le radar / serveur en attente si aucun transfert n'est en cours
            val cur = TransferManager.state.value
            if (cur.status == TransferStatus.READY || cur.status == TransferStatus.WAITING_NET) {
                if (cur.role == TransferRole.RECEIVING) TransferManager.stop()
            }
        }
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Retour", onClick = onBack)
                Spacer(Modifier.width(8.dp))
                ScreenTitle("TRANSFERT")
                Spacer(Modifier.weight(1f))
                GlassIconButton(Icons.Rounded.Refresh, "Actualiser réseau") { TransferManager.refreshNetwork() }
            }

            // Onglets ENVOYER / RECEVOIR
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).glass(RoundedCornerShape(50), VG.Indigo, 8.dp).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ModeTab("ENVOYER", tab == 0, Modifier.weight(1f)) {
                    tab = 0
                    if (st.role != TransferRole.SENDING) TransferManager.startSending(emptyList(), st.includeApk)
                }
                ModeTab("RECEVOIR", tab == 1, Modifier.weight(1f)) {
                    tab = 1
                    if (st.role != TransferRole.RECEIVING) TransferManager.startReceivingMode()
                }
            }

            Spacer(Modifier.height(10.dp))

            if (st.status == TransferStatus.WAITING_NET) {
                NetworkPromptBar(
                    onWifi = { LocalNetHelper.openWifiPanel(ctx) },
                    onHotspot = { LocalNetHelper.openHotspotSettings(ctx) }
                )
            }

            if (tab == 0) {
                SendView(
                    st = st,
                    items = if (showPrivateInSend) prv else pub,
                    showingPrivate = showPrivateInSend,
                    privateUnlocked = privateUnlocked,
                    onSelectSource = { wantPrivate ->
                        if (!wantPrivate) {
                            showPrivateInSend = false
                        } else if (!AppPrefs.hasPin() || privateUnlocked) {
                            privateUnlocked = true
                            vm.refreshPrivate()
                            showPrivateInSend = true
                        } else {
                            showPrivatePinDlg = true
                        }
                    }
                )
            } else {
                ReceiveView(
                    st = st,
                    onPickPeer = { pinPeer = it },
                    onManual = { manualDlg = true },
                    onPlayFile = { f ->
                        val uri = f.savedUri ?: return@ReceiveView
                        if (f.isApk) {
                            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(i) }
                        } else {
                            ctx.startActivity(
                                Intent(ctx, PlayerActivity::class.java).setData(uri).putExtra("key", f.savedKey).putExtra("title", f.name)
                            )
                        }
                    }
                )
            }
        }
    }

    if (showPrivatePinDlg) {
        PinDialog(
            mode = PinMode.VERIFY,
            onSuccess = {
                showPrivatePinDlg = false
                privateUnlocked = true
                vm.refreshPrivate()
                showPrivateInSend = true
            },
            onDismiss = { showPrivatePinDlg = false }
        )
    }

    pinPeer?.let { p ->
        TransferPinDialog(
            peerName = p.deviceName,
            errorText = st.error,
            onSubmit = { code ->
                TransferManager.connectAndReceive(p.ip, p.port, code)
                pinPeer = null
            },
            onDismiss = { pinPeer = null }
        )
    }

    if (manualDlg) {
        var ip by remember { mutableStateOf("192.168.43.1") }
        var portText by remember { mutableStateOf("8765") }
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manualDlg = false },
            title = { Text("Connexion manuelle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            ip,
                            { ip = it },
                            label = { Text("Adresse IP") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            portText,
                            { if (it.length <= 5) portText = it.filter(Char::isDigit) },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        pin,
                        { if (it.length <= 4) pin = it.filter(Char::isDigit) },
                        label = { Text("Code 4 chiffres") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleanIp = ip.trim().substringBefore(':')
                    val inlinePort = ip.trim().substringAfter(':', "").toIntOrNull()
                    val port = (inlinePort ?: portText.toIntOrNull() ?: 8765).coerceIn(1, 65535)
                    if (cleanIp.isNotBlank() && pin.length == 4) {
                        TransferManager.connectAndReceive(cleanIp, port, pin)
                        manualDlg = false
                    }
                }) { Text("Connecter") }
            },
            dismissButton = { TextButton(onClick = { manualDlg = false }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50))
            .background(if (selected) VG.brand else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
            .bounceClick(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun NetworkPromptBar(onWifi: () -> Unit, onHotspot: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), glow = VG.Coral) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WifiOff, null, tint = VG.Coral)
                Spacer(Modifier.width(10.dp))
                Text("Activez le Wi-Fi ou le Point d'accès pour transférer hors ligne.", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallNetBtn(Icons.Rounded.Wifi, "Wi-Fi", VG.Cyan, Modifier.weight(1f), onWifi)
                SmallNetBtn(Icons.Rounded.WifiTethering, "Point d'accès", VG.Indigo, Modifier.weight(1f), onHotspot)
            }
        }
    }
}

@Composable
private fun SmallNetBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.18f)).bounceClick(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun SendView(
    st: TransferState,
    items: List<LibraryItem>,
    showingPrivate: Boolean,
    privateUnlocked: Boolean,
    onSelectSource: (Boolean) -> Unit
) {
    val picked = remember { mutableStateListOf<LibraryItem>() }
    if (st.files.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Vidéos à envoyer", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = !showingPrivate,
                    onClick = { onSelectSource(false) },
                    label = { Text("Publiques", fontSize = 12.sp) }
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = showingPrivate,
                    onClick = { onSelectSource(true) },
                    leadingIcon = {
                        Icon(
                            if (privateUnlocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                            null,
                            modifier = Modifier.size(15.dp)
                        )
                    },
                    label = { Text("Privées", fontSize = 12.sp) }
                )
            }
            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (showingPrivate) "Aucune vidéo dans le dossier privé" else "Aucune vidéo téléchargée",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.key }) { item ->
                        val checked = picked.any { it.key == item.key }
                        GlassCard(
                            Modifier.fillMaxWidth(), glow = if (checked) VG.Cyan else VG.Indigo,
                            onClick = { if (checked) picked.removeAll { it.key == item.key } else picked.add(item) }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(Format.size(item.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            GradientButton("Envoyer (${picked.size})", Modifier.fillMaxWidth(), Icons.Rounded.Send) {
                if (picked.isNotEmpty()) TransferManager.startSending(picked.toList(), st.includeApk)
            }
            Spacer(Modifier.height(14.dp))
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (st.pin.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth(), glow = VG.Cyan) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CODE DE SÉCURITÉ", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = VG.Cyan)
                        Text(st.pin, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = 10.sp, brush = VG.brand))
                        Text("${st.totalFiles} fichier(s) · ${Format.size(st.totalBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (st.localIp.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Navigateur : http://${st.localIp}:${st.port}", fontSize = 11.sp, color = VG.Cyan)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().clickable { TransferManager.toggleIncludeApk(!st.includeApk) }, verticalAlignment = Alignment.CenterVertically) {
                            Text("Inclure l'app VidGrab (.apk)", Modifier.weight(1f), fontSize = 13.sp)
                            Switch(checked = st.includeApk, onCheckedChange = { TransferManager.toggleIncludeApk(it) })
                        }
                    }
                }
            }
        }
        item { LiveSummaryCard(st) }
        items(st.files, key = { it.index }) { f -> FileRowCard(f, clickable = false) {} }
        item {
            Spacer(Modifier.height(4.dp))
            GradientButton("Arrêter le partage", Modifier.fillMaxWidth(), Icons.Rounded.Close, brush = VG.alert, glow = VG.Coral) {
                TransferManager.stop()
            }
        }
    }
}

@Composable
private fun ReceiveView(
    st: TransferState,
    onPickPeer: (PeerInfo) -> Unit,
    onManual: () -> Unit,
    onPlayFile: (TransferFileState) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard(Modifier.fillMaxWidth(), glow = VG.Indigo) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = VG.Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Enregistrer dans le Dossier Privé", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = st.savePrivate, onCheckedChange = { TransferManager.setSavePrivate(it) })
                }
            }
        }

        if (st.status == TransferStatus.READY || st.status == TransferStatus.WAITING_NET) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RadarAnimation(active = st.status == TransferStatus.READY)
                    Spacer(Modifier.height(8.dp))
                    Text(st.message, fontSize = 13.sp, color = if (st.error != null) VG.Coral else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            items(st.peers, key = { "${it.ip}:${it.port}" }) { peer ->
                GlassCard(Modifier.fillMaxWidth(), glow = VG.Cyan, onClick = { onPickPeer(peer) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhoneAndroid, null, tint = VG.Cyan, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(peer.deviceName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${peer.fileCount} fichier(s) · ${Format.size(peer.totalBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Recevoir", color = VG.Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onManual) {
                        Icon(Icons.Rounded.Router, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Saisir l'adresse manuellement")
                    }
                }
            }
        } else {
            item { LiveSummaryCard(st) }
            items(st.files, key = { it.index }) { f -> FileRowCard(f, clickable = f.done && f.savedUri != null) { onPlayFile(f) } }
            item {
                GradientButton(
                    if (st.status == TransferStatus.DONE) "Nouveau transfert" else "Annuler",
                    Modifier.fillMaxWidth(),
                    brush = if (st.status == TransferStatus.DONE) VG.brand else VG.alert
                ) { TransferManager.startReceivingMode() }
            }
        }
    }
}

@Composable
private fun LiveSummaryCard(st: TransferState) {
    val total = st.totalBytes.coerceAtLeast(1L)
    val frac = (st.transferredBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    GlassCard(Modifier.fillMaxWidth(), glow = if (st.status == TransferStatus.ERROR) VG.Coral else VG.Cyan) {
        Column(Modifier.padding(16.dp)) {
            Text(st.message, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            ShimmerProgress(frac, active = st.status == TransferStatus.TRANSFERRING)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${st.completedFiles}/${st.totalFiles} · ${Format.size(st.transferredBytes)} / ${Format.size(st.totalBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Format.speed(st.speedBps)} · ${Format.eta(st.etaSec)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VG.Cyan)
            }
        }
    }
}

@Composable
private fun FileRowCard(f: TransferFileState, clickable: Boolean, onClick: () -> Unit) {
    val frac = if (f.sizeBytes > 0L) (f.transferredBytes.toDouble() / f.sizeBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    GlassCard(Modifier.fillMaxWidth(), glow = if (f.done) VG.Cyan else VG.Indigo, onClick = if (clickable) onClick else null) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (f.done) Icons.Rounded.CheckCircle else Icons.Rounded.PlayArrow, null,
                tint = if (f.done) VG.Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${f.name}.${f.ext}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${Format.size(f.transferredBytes)} / ${Format.size(f.sizeBytes)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${(frac * 100f).toInt()} %", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VG.Cyan)
        }
    }
}

@Composable
private fun RadarAnimation(active: Boolean) {
    val t = rememberInfiniteTransition(label = "tx-radar")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.size(110.dp)) {
                for (k in 0 until 3) {
                    val ph = (phase + k / 3f) % 1f
                    drawCircle(VG.Cyan.copy(alpha = (1f - ph) * 0.6f), radius = 18.dp.toPx() + 36.dp.toPx() * ph, style = Stroke(2.dp.toPx()))
                }
            }
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(VG.brand), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.WifiTethering, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun TransferPinDialog(peerName: String, errorText: String?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), VG.Cyan, 20.dp, alpha = 0.96f).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Code affiché sur $peerName", fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
            if (!errorText.isNullOrBlank()) Text(errorText, color = VG.Coral, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    val filled = i < pin.length
                    Box(
                        Modifier.size(15.dp)
                            .background(if (filled) VG.brand else androidx.compose.ui.graphics.SolidColor(Color.Transparent), CircleShape)
                            .border(1.5.dp, if (filled) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    row.forEach { k ->
                        if (k.isEmpty()) Spacer(Modifier.size(58.dp))
                        else Box(
                            Modifier.size(58.dp).glass(CircleShape, VG.Cyan, 6.dp).bounceClick {
                                if (k == "⌫") { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
                                else if (pin.length < 4) {
                                    pin += k
                                    if (pin.length == 4) onSubmit(pin)
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            if (k == "⌫") Icon(Icons.Rounded.Backspace, null, modifier = Modifier.size(20.dp))
                            else Text(k, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}