package com.vidgrab.app.ui.browser

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.download.BatchSeriesManager
import com.vidgrab.app.ui.components.GlassIconButton
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.components.glass
import com.vidgrab.app.ui.theme.VG
import com.vidgrab.app.web.TabManager
import com.vidgrab.app.web.TabState

@Composable
fun WebScreen(tab: TabState, onOpenTabs: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val bookmarks by AppPrefs.bookmarks.collectAsStateWithLifecycle()
    val starred = bookmarks.any { it.url == tab.url }
    var editing by remember(tab.id) { mutableStateOf(false) }
    var text by remember(tab.id) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showBatch by remember(tab.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- Barre supérieure ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TabCountButton(TabManager.tabs.size, onOpenTabs)
            GlassIconButton(Icons.Rounded.Home, "Accueil") { TabManager.goHome(tab) }

            // Barre d'adresse compacte
            Row(
                Modifier.weight(1f).height(44.dp).glass(RoundedCornerShape(50), VG.Cyan, 8.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val secure = tab.url.startsWith("https://")
                Icon(
                    if (secure) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null,
                    Modifier.size(16.dp), tint = if (secure) VG.Cyan else VG.Coral
                )
                Spacer(Modifier.width(8.dp))
                if (editing) {
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(Unit) { fr.requestFocus() }
                    BasicTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = Brush.verticalGradient(listOf(VG.Cyan, VG.Indigo)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                        keyboardActions = KeyboardActions(onGo = {
                            editing = false
                            TabManager.load(tab, text)
                        }),
                        modifier = Modifier.weight(1f).focusRequester(fr)
                    )
                } else {
                    Text(
                        AppPrefs.hostOf(tab.url) + tab.url.substringAfter(AppPrefs.hostOf(tab.url), "").take(40),
                        Modifier.weight(1f).bounceClick { text = tab.url; editing = true },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp
                    )
                }
                // Étoile animée : rotation + changement de couleur dorée
                val rot by animateFloatAsState(
                    if (starred) 360f else 0f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "star-rot"
                )
                val starColor by animateColorAsState(if (starred) VG.Gold else MaterialTheme.colorScheme.onSurfaceVariant, label = "star-color")
                Box(
                    Modifier.size(36.dp).bounceClick {
                        val added = AppPrefs.toggleBookmark(tab.title, tab.url)
                        haptic.performHapticFeedback(if (added) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favori",
                        Modifier.size(22.dp).graphicsLayer { rotationZ = rot }, tint = starColor
                    )
                }
                Box(
                    Modifier.size(36.dp).bounceClick {
                        if (editing) { editing = false } else if (tab.loading) TabManager.stop(tab) else TabManager.goHome(tab)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, "Arrêter", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Box {
                GlassIconButton(Icons.Rounded.MoreVert, "Menu") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null, tint = VG.Cyan) },
                        text = { Text("Mode Série (télécharger une suite)") },
                        onClick = { menu = false; showBatch = true }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null) },
                        text = { Text("Version ordinateur") },
                        trailingIcon = { Switch(checked = tab.desktop, onCheckedChange = null) },
                        onClick = { TabManager.setDesktop(tab, !tab.desktop) }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, text = { Text("Actualiser") },
                        onClick = { menu = false; TabManager.reload(tab) }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.Share, null) }, text = { Text("Partager le lien") },
                        onClick = {
                            menu = false
                            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, tab.url) }
                            ctx.startActivity(Intent.createChooser(i, "Partager le lien"))
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) }, text = { Text("Ouvrir dans le navigateur externe") },
                        onClick = {
                            menu = false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url))) }
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = VG.Coral) }, text = { Text("Tout effacer") },
                        onClick = { menu = false; TabManager.clearAll() }
                    )
                }
            }
        }

        // ---- Barre de progression lumineuse (Cyan → Indigo) ----
        WebProgressBar(loading = tab.loading, progress = tab.progress)

        // ---- Bandeau Mode Série ----
        BatchBanner(tab.id)

        // ---- WebView plein écran ----
        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 0.dp)) {
            key(tab.id) {
                AndroidView(
                    factory = {
                        val wv = TabManager.webViewFor(tab)
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv
                    },
                    update = { it.onResume() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            val custom = tab.customView
            if (custom != null) {
                AndroidView(
                    factory = {
                        (custom.parent as? ViewGroup)?.removeView(custom)
                        custom
                    },
                    modifier = Modifier.fillMaxSize().background(Color.Black)
                )
            }
        }
    }

    if (showBatch) {
        BatchSetupDialog(
            tab = tab,
            onStart = { cfg -> showBatch = false; BatchSeriesManager.start(cfg) },
            onDismiss = { showBatch = false }
        )
    }
}

@Composable
private fun WebProgressBar(loading: Boolean, progress: Float) {
    Box(Modifier.fillMaxWidth().height(3.dp)) {
        AnimatedVisibility(visible = loading, enter = fadeIn(), exit = fadeOut()) {
            val p by animateFloatAsState(progress.coerceIn(0.03f, 1f), label = "web-progress")
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(p)
                    .shadow(6.dp, RoundedCornerShape(50), ambientColor = VG.Cyan, spotColor = VG.Cyan)
                    .background(VG.progressBrush)
            )
        }
    }
}
