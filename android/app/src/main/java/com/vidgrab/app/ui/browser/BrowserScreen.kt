package com.vidgrab.app.ui.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import com.vidgrab.app.ui.MainViewModel
import com.vidgrab.app.web.TabManager

/** Onglet « Navigation web » : accueil ou WebView active + FAB de détection 3D + feuille des vidéos. */
@Composable
fun BrowserScreen(
    vm: MainViewModel,
    onOpenTabs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit
) {
    val tab = TabManager.current
    var sheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (sheet) Modifier.blur(14.dp) else Modifier)) {
            AnimatedContent(
                targetState = tab.id to tab.isHome,
                transitionSpec = {
                    (fadeIn(tween(260)) + scaleIn(initialScale = 0.96f, animationSpec = tween(260))) togetherWith fadeOut(tween(140))
                },
                label = "browser-content"
            ) { (id, home) ->
                val t = TabManager.tabs.firstOrNull { it.id == id } ?: tab
                if (home) HomeScreen(vm, t, onOpenTabs, onOpenSettings, onOpenHelp)
                else WebScreen(t, onOpenTabs)
            }
        }

        if (!tab.isHome) {
            DetectFab(
                count = tab.videos.size,
                onClick = { sheet = true },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(bottom = 68.dp)
            )
        }
        if (sheet) VideoSheet(tab, tab.videos.toList(), onDismiss = { sheet = false })
    }
}
