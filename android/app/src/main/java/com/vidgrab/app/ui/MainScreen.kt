package com.vidgrab.app.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vidgrab.app.data.BatchStatus
import com.vidgrab.app.download.BatchSeriesManager
import com.vidgrab.app.download.DownloadCenter
import com.vidgrab.app.transfer.TransferManager
import com.vidgrab.app.ui.browser.BrowserScreen
import com.vidgrab.app.ui.browser.HelpGuide
import com.vidgrab.app.ui.browser.TabSwitcher
import com.vidgrab.app.ui.components.AuroraBackground
import com.vidgrab.app.ui.components.FloatingNavBar
import com.vidgrab.app.ui.components.GlassCard
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.downloads.ProgressScreen
import com.vidgrab.app.ui.library.LibraryScreen
import com.vidgrab.app.ui.settings.SettingsScreen
import com.vidgrab.app.ui.theme.VG
import com.vidgrab.app.ui.transfer.TransferScreen
import com.vidgrab.app.web.TabManager

/** Scaffold principal : 3 onglets + barre flottante + Robot en arrière-plan + Transfert hors ligne. */
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showTabs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val tasks by DownloadCenter.tasks.collectAsStateWithLifecycle()
    val tx by TransferManager.state.collectAsStateWithLifecycle()
    val batch by BatchSeriesManager.state.collectAsStateWithLifecycle()
    val tab = TabManager.current
    val batchTab = batch.config?.tabId?.let { id -> TabManager.tabs.firstOrNull { it.id == id } }
    val robotVisibleInWebScreen = page == 0 && !tab.isHome && tab.id == batchTab?.id

    LaunchedEffect(Unit) {
        DownloadCenter.events.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    LaunchedEffect(batch.status, batch.config?.tabId) {
        if (batch.status == BatchStatus.NEEDS_USER && batchTab != null) {
            TabManager.switchTo(batchTab)
            showTransfer = false
            showTabs = false
            showSettings = false
            showHelp = false
            page = 0
        }
    }

    BackHandler(enabled = showTransfer || showTabs || showSettings || page != 0 || !tab.isHome || tab.customView != null) {
        when {
            showTransfer -> showTransfer = false
            showTabs -> showTabs = false
            showSettings -> showSettings = false
            page == 0 && tab.customView != null -> {
                tab.customCallback?.onCustomViewHidden()
                tab.customCallback = null
                tab.customView = null
            }
            page != 0 -> page = 0
            tab.webView?.canGoBack() == true -> tab.webView?.goBack()
            else -> TabManager.goHome(tab)
        }
    }

    val openTabs = {
        TabManager.capturePreview(TabManager.current)
        showTabs = true
    }

    Box(Modifier.fillMaxSize()) {
        val keepRobotAlive = batchTab != null &&
            batch.status != BatchStatus.IDLE &&
            batch.status != BatchStatus.DONE &&
            !robotVisibleInWebScreen

        if (keepRobotAlive && batchTab != null) {
            key(batchTab.id) {
                AndroidView(
                    factory = {
                        val wv = TabManager.webViewFor(batchTab)
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv
                    },
                    update = { it.onResume() },
                    modifier = Modifier.fillMaxSize().alpha(0f)
                )
            }
        }

        val guardModifier = if (keepRobotAlive) {
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Final)
                        ev.changes.forEach { if (!it.isConsumed) it.consume() }
                    }
                }
            }
        } else Modifier.fillMaxSize()

        AuroraBackground(guardModifier) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(240)) + scaleIn(initialScale = 0.94f, animationSpec = tween(240))) togetherWith
                        (fadeOut(tween(120)) + scaleOut(targetScale = 1.04f, animationSpec = tween(120)))
                },
                label = "pages"
            ) { p ->
                when (p) {
                    0 -> BrowserScreen(vm, onOpenTabs = openTabs, onOpenSettings = { showSettings = true }, onOpenHelp = { showHelp = true })
                    1 -> ProgressScreen(onOpenTransfer = { showTransfer = true })
                    else -> LibraryScreen(vm, onOpenTransfer = { showTransfer = true })
                }
            }

            AnimatedVisibility(
                visible = batch.status != BatchStatus.IDLE && !robotVisibleInWebScreen && page != 1,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 22.dp, end = 124.dp, bottom = 100.dp)
            ) {
                val done = batch.status == BatchStatus.DONE
                val glow = if (done) VG.Indigo else VG.Cyan
                GlassCard(
                    shape = RoundedCornerShape(20.dp),
                    glow = glow,
                    onClick = {
                        if (done) page = 1
                        else if (batchTab != null) {
                            TabManager.switchTo(batchTab)
                            page = 0
                        }
                    }
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = glow, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (done) "Détection terminée" else "Robot · Ép. ${batch.currentEp}/${batch.config?.endEp ?: batch.totalEps}",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = glow, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                batch.message, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(VG.Coral.copy(alpha = 0.18f))
                                .bounceClick { BatchSeriesManager.stop() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Close, "Fermer", tint = VG.Coral, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            FloatingNavBar(
                selected = page,
                onSelect = { page = it },
                downloadCount = tasks.size + if (tx.isActive) 1 else 0,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        AnimatedVisibility(
            visible = showTabs,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.9f, animationSpec = tween(260)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.92f, animationSpec = tween(160))
        ) { TabSwitcher(onClose = { showTabs = false }) }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it / 3 } + fadeIn(),
            exit = slideOutVertically { it / 3 } + fadeOut()
        ) { SettingsScreen(onBack = { showSettings = false }) }

        AnimatedVisibility(
            visible = showTransfer,
            enter = slideInVertically { it / 3 } + fadeIn(),
            exit = slideOutVertically { it / 3 } + fadeOut()
        ) { TransferScreen(vm, onBack = { showTransfer = false }) }

        if (showHelp) HelpGuide(onDismiss = { showHelp = false })
    }
}
