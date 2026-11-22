package com.vidgrab.app.ui.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.ui.components.GlassCard
import com.vidgrab.app.ui.components.GradientButton
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.components.staggerIn
import com.vidgrab.app.ui.theme.VG
import com.vidgrab.app.web.TabManager
import com.vidgrab.app.web.TabState
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Gestionnaire d'onglets : pile de cartes en perspective 3D (rotationX), glissement latéral pour fermer
 * avec rotation (rotationY) et rétraction dans l'espace (scale + fondu).
 */
@Composable
fun TabSwitcher(onClose: () -> Unit) {
    val tabs = TabManager.tabs
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xF00B0D14), Color(0xFA121624))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ONGLETS · ${tabs.size}",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 3.sp, brush = VG.brand)
                )
                Spacer(Modifier.weight(1f))
                Text("OK", Modifier.bounceClick(onClick = onClose).padding(8.dp), color = VG.Cyan, fontWeight = FontWeight.Bold)
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 190.dp)
            ) {
                itemsIndexed(tabs, key = { _, t -> t.id }) { i, tab ->
                    TabCard(
                        tab, i, selected = tab === TabManager.current,
                        onOpen = { TabManager.switchTo(tab); onClose() },
                        onDismiss = { TabManager.closeTab(tab) }
                    )
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)) {
            GradientButton("Nouvel onglet", icon = Icons.Rounded.Add, onClick = {
                TabManager.openNewTab()
                onClose()
            })
        }
    }
}

@Composable
private fun TabCard(tab: TabState, index: Int, selected: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(24.dp)

    fun flyOut(direction: Float) {
        scope.launch {
            offsetX.animateTo(direction * widthPx * 1.2f, tween(240))
            onDismiss()
        }
    }

    Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 24.dp).zIndex(index.toFloat()).staggerIn(index)) {
        Box(
            Modifier.fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .height(236.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    val frac: Float = (offsetX.value / widthPx).coerceIn(-1f, 1f)
                    translationX = offsetX.value
                    rotationY = frac * 70f
                    rotationX = -12f * (1f - abs(frac))
                    val s: Float = 1f - abs(frac) * 0.4f
                    scaleX = s
                    scaleY = s
                    alpha = 1f - abs(frac) * 0.85f
                    cameraDistance = 16f * density
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = offsetX.value
                            if (abs(v) > widthPx * 0.3f) flyOut(if (v > 0f) 1f else -1f)
                            else scope.launch { offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + dx) }
                        }
                    )
                }
        ) {
            val c = colorFor(tab.url.ifBlank { "home${tab.id}" })
            GlassCard(
                Modifier.fillMaxSize().then(if (selected) Modifier.border(2.dp, VG.brand, shape) else Modifier),
                shape = shape, glow = c, onClick = onOpen
            ) {
                val preview = tab.preview
                if (preview != null && !tab.isHome) {
                    Image(preview, null, Modifier.fillMaxSize().padding(top = 52.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                } else {
                    Box(
                        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.copy(alpha = 0.28f), Color.Transparent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (tab.isHome) "⌂" else AppPrefs.hostOf(tab.url).take(1).uppercase(),
                            fontSize = 72.sp, fontWeight = FontWeight.Black, color = c.copy(alpha = 0.55f)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                        Text(
                            if (tab.isHome) "V" else AppPrefs.hostOf(tab.url).take(1).uppercase(),
                            color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (tab.isHome) "Accueil" else tab.title.ifBlank { AppPrefs.hostOf(tab.url) },
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                    )
                    Box(Modifier.size(36.dp).bounceClick { flyOut(1f) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Fermer l'onglet", Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
