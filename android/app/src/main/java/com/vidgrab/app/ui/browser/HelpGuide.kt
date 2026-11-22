package com.vidgrab.app.ui.browser

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vidgrab.app.ui.components.AuroraBackground
import com.vidgrab.app.ui.components.GradientButton
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.theme.VG
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class Step(val icon: ImageVector, val title: String, val text: String, val a: Color, val b: Color)

private val STEPS = listOf(
    Step(Icons.Rounded.Public, "1. Ouvrez un site vidéo", "Tapez une adresse ou choisissez un raccourci (Vimeo, Dailymotion, Facebook…) depuis l'accueil.", VG.Indigo, VG.Cyan),
    Step(Icons.Rounded.PlayCircle, "2. Lancez la lecture", "Touchez le bouton lecture de la vidéo. VidGrab analyse le trafic réseau en arrière-plan, même pour les flux camouflés.", VG.Cyan, VG.Indigo),
    Step(Icons.Rounded.Download, "3. Touchez le bouton rouge", "Le bouton flottant pivote en 3D et affiche le nombre de flux détectés. Touchez-le pour choisir la qualité.", VG.Coral, VG.Magenta),
    Step(Icons.Rounded.Downloading, "4. Suivez la progression", "Les téléchargements continuent en arrière-plan. Retrouvez vos vidéos dans l'onglet « Terminé ».", VG.Magenta, VG.Indigo)
)

/** Guide interactif étape par étape. */
@Composable
fun HelpGuide(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AuroraBackground {
            val pager = rememberPagerState(pageCount = { STEPS.size })
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current.density
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Comment télécharger", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, brush = VG.brand))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(40.dp).bounceClick(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Fermer")
                    }
                }
                HorizontalPager(pager, Modifier.weight(1f)) { page ->
                    val s = STEPS[page]
                    val offset: Float = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    val pulse = rememberInfiniteTransition(label = "help")
                    val k by pulse.animateFloat(0.96f, 1.06f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "k")
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            rotationY = offset * -30f
                            alpha = 1f - abs(offset) * 0.6f
                            cameraDistance = 16f * density
                        },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(170.dp).graphicsLayer { scaleX = k; scaleY = k }
                                .shadow(30.dp, CircleShape, ambientColor = s.a, spotColor = s.b)
                                .background(Brush.linearGradient(listOf(s.a, s.b)), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Icon(s.icon, null, Modifier.size(84.dp), tint = Color.White) }
                        Spacer(Modifier.height(36.dp))
                        Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(s.text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                    repeat(STEPS.size) { i ->
                        val w by animateFloatAsState(if (pager.currentPage == i) 26f else 8f, label = "dot")
                        Box(
                            Modifier.padding(4.dp).size(width = w.dp, height = 8.dp)
                                .background(if (pager.currentPage == i) VG.Cyan else Color.White.copy(alpha = 0.25f), CircleShape)
                        )
                    }
                }
                val last = pager.currentPage == STEPS.size - 1
                GradientButton(if (last) "C'est parti !" else "Suivant", Modifier.fillMaxWidth(), onClick = {
                    if (last) onDismiss() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                })
            }
        }
    }
}
