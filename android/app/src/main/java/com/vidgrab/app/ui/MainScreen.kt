package com.vidgrab.app.ui

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vidgrab.app.download.DownloadCenter
import com.vidgrab.app.ui.browser.BrowserScreen
import com.vidgrab.app.ui.browser.HelpGuide
import com.vidgrab.app.ui.browser.TabSwitcher
import com.vidgrab.app.ui.components.AuroraBackground
import com.vidgrab.app.ui.components.FloatingNavBar
import com.vidgrab.app.ui.downloads.ProgressScreen
import com.vidgrab.app.ui.library.LibraryScreen
import com.vidgrab.app.ui.settings.SettingsScreen
import com.vidgrab.app.web.TabManager

/** Scaffold principal : 3 onglets + barre flottante en verre + superpositions (onglets 3D, aide, paramètres). */
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showTabs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val tasks by DownloadCenter.tasks.collectAsStateWithLifecycle()
    val tab = TabManager.current

    LaunchedEffect(Unit) {
        DownloadCenter.events.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    BackHandler(enabled = showTabs || showSettings || page != 0 || !tab.isHome || tab.customView != null) {
        when {
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
        AuroraBackground {
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
                    1 -> ProgressScreen()
                    else -> LibraryScreen(vm)
                }
            }

            FloatingNavBar(
                selected = page, onSelect = { page = it }, downloadCount = tasks.size,
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

        if (showHelp) HelpGuide(onDismiss = { showHelp = false })
    }
}
