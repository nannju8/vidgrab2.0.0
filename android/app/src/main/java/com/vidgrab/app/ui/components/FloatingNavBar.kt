package com.vidgrab.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.ui.theme.VG

private data class NavEntry(val label: String, val icon: ImageVector)

/** Barre de navigation flottante en verre dépoli (coins 28 dp), icône active surélevée avec halo. */
@Composable
fun FloatingNavBar(selected: Int, onSelect: (Int) -> Unit, downloadCount: Int, modifier: Modifier = Modifier) {
    val items = listOf(
        NavEntry("Onglet", Icons.Rounded.Public),
        NavEntry("Progression", Icons.Rounded.Downloading),
        NavEntry("Terminé", Icons.Rounded.VideoLibrary)
    )
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .shadow(26.dp, shape, ambientColor = Color.Black, spotColor = VG.Indigo.copy(alpha = 0.55f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.84f))
            .border(1.dp, glassBorderBrush(), shape)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, e ->
                NavItem(e, selected == i, if (i == 1) downloadCount else 0) { onSelect(i) }
            }
        }
    }
}

@Composable
private fun NavItem(entry: NavEntry, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val lift by animateDpAsState(
        if (selected) (-6).dp else 0.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "lift"
    )
    val halo by animateFloatAsState(if (selected) 1f else 0f, spring(stiffness = Spring.StiffnessLow), label = "halo")
    Column(
        Modifier.width(92.dp).bounceClick(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(46.dp).offset(y = lift), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(46.dp).graphicsLayer { alpha = halo }
                    .background(
                        Brush.radialGradient(listOf(VG.Indigo.copy(alpha = 0.55f), VG.Cyan.copy(alpha = 0.18f), Color.Transparent)),
                        CircleShape
                    )
            )
            val tintMod = if (selected) {
                Modifier.size(26.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(VG.brand, blendMode = BlendMode.SrcIn)
                    }
            } else Modifier.size(24.dp)
            Icon(entry.icon, entry.label, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = tintMod)

            NavBadge(
                badge = badge,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 0.dp)
            )
        }
        Text(
            entry.label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NavBadge(badge: Int, modifier: Modifier = Modifier) {
    Box(modifier) {
        AnimatedVisibility(
            visible = badge > 0,
            enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut() + fadeOut()
        ) {
            Box(
                Modifier.size(18.dp).shadow(6.dp, CircleShape, ambientColor = VG.Coral, spotColor = VG.Coral)
                    .background(VG.alert, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = badge,
                    transitionSpec = {
                        (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
                    }, label = "badge"
                ) { n ->
                    Text(n.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}