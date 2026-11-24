package com.vidgrab.app.ui.browser

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.data.Bookmark
import com.vidgrab.app.data.SitesSyncApi
import com.vidgrab.app.ui.MainViewModel
import com.vidgrab.app.ui.components.GlassCard
import com.vidgrab.app.ui.components.GlassIconButton
import com.vidgrab.app.ui.components.GlassSquareButton
import com.vidgrab.app.ui.components.GradientButton
import com.vidgrab.app.ui.components.SectionLabel
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.components.glass
import com.vidgrab.app.ui.components.glassBorderBrush
import com.vidgrab.app.ui.components.staggerIn
import com.vidgrab.app.ui.theme.VG
import com.vidgrab.app.web.TabManager
import com.vidgrab.app.web.TabState
import kotlinx.coroutines.launch

private data class Shortcut(val name: String, val letter: String, val color: Color, val url: String)

private val SHORTCUTS = listOf(
    Shortcut("Facebook", "f", Color(0xFF1877F2), "https://m.facebook.com"),
    Shortcut("Instagram", "I", Color(0xFFE1306C), "https://www.instagram.com"),
    Shortcut("Vimeo", "V", Color(0xFF1AB7EA), "https://vimeo.com"),
    Shortcut("Dailymotion", "D", Color(0xFF0066DC), "https://www.dailymotion.com"),
    Shortcut("TikTok", "T", Color(0xFFFE2C55), "https://www.tiktok.com")
)

fun colorFor(seed: String): Color {
    val palette = listOf(VG.Indigo, VG.Cyan, VG.Magenta, Color(0xFFF59E0B), Color(0xFF10B981), VG.Coral, Color(0xFF8B5CF6))
    return palette[(seed.hashCode() and 0x7FFFFFFF) % palette.size]
}

/** Bouton carré affichant le nombre d'onglets. */
@Composable
fun TabCountButton(count: Int, onClick: () -> Unit) {
    GlassSquareButton(onClick = onClick, size = 44.dp) {
        Box(
            Modifier.size(22.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun HomeScreen(
    vm: MainViewModel,
    tab: TabState,
    onOpenTabs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookmarks by AppPrefs.bookmarks.collectAsStateWithLifecycle()
    val history by AppPrefs.history.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val google by vm.googleSuggestions.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    var editTarget by remember { mutableStateOf<Bookmark?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    fun open(input: String) {
        TabManager.load(tab, input)
        vm.query.value = ""
        focus.clearFocus()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 130.dp)
    ) {
        // ---- Barre supérieure ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TabCountButton(TabManager.tabs.size, onOpenTabs)
            Spacer(Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.QuestionMark, "Aide", onClick = onOpenHelp)
            GlassIconButton(Icons.Rounded.Settings, "Paramètres", onClick = onOpenSettings)
            Box {
                GlassIconButton(Icons.Rounded.MoreVert, "Menu") { moreMenu = true }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("Nouvel onglet") }, onClick = { moreMenu = false; TabManager.openNewTab() })
                    DropdownMenuItem(text = { Text("Ajouter un site") }, onClick = { moreMenu = false; addDialog = true })
                    DropdownMenuItem(text = { Text("Effacer l'historique") }, onClick = { moreMenu = false; AppPrefs.clearHistory() })
                    DropdownMenuItem(text = { Text("Paramètres") }, onClick = { moreMenu = false; onOpenSettings() })
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "VidGrab",
            modifier = Modifier.padding(horizontal = 24.dp).staggerIn(0),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, brush = VG.brand)
        )
        Text(
            "Navigue. Détecte. Télécharge.",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp).staggerIn(1),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
        )
        Spacer(Modifier.height(18.dp))

        // ---- Barre de recherche intelligente ----
        SmartSearchBar(query, { vm.query.value = it }, onSubmit = { if (query.isNotBlank()) open(query) })

        val q = query.trim().lowercase()
        val favMatches = if (q.isBlank()) emptyList() else bookmarks.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }.take(3)
        val histMatches = if (q.isBlank()) emptyList() else history.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }.take(3)
        AnimatedVisibility(
            visible = q.isNotBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), shape = RoundedCornerShape(22.dp), glow = VG.Cyan) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    favMatches.forEach { SuggestionRow(Icons.Rounded.Star, it.title, it.url, VG.Gold) { open(it.url) } }
                    histMatches.forEach { SuggestionRow(Icons.Rounded.History, it.title, it.url, VG.Cyan) { open(it.url) } }
                    google.forEach { SuggestionRow(Icons.Rounded.Search, it, null, VG.Indigo) { open(it) } }
                    if (favMatches.isEmpty() && histMatches.isEmpty() && google.isEmpty()) {
                        SuggestionRow(Icons.Rounded.Search, "Rechercher « $query »", null, VG.Indigo) { open(query) }
                    }
                }
            }
        }

        // ---- Raccourcis 3D ----
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SHORTCUTS.forEachIndexed { i, s ->
                Column(
                    Modifier.width(64.dp).staggerIn(i + 2),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(56.dp)
                            .shadow(14.dp, CircleShape, ambientColor = s.color, spotColor = s.color)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(s.color.copy(alpha = 0.75f), s.color)))
                            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent)), CircleShape)
                            .bounceClick { open(s.url) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(s.letter, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(s.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---- Sites enregistrés & favoris ----
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Sites enregistrés")
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable {
                    scope.launch {
                        val res = SitesSyncApi.fetchRemoteSites()
                        res.onSuccess { list ->
                            val updated = AppPrefs.mergeSyncedBookmarks(list)
                            val msg = if (updated > 0) "$updated lien(s) mis à jour" else "Tous les liens sont déjà à jour"
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(ctx, "Synchronisation impossible hors ligne", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp), tint = VG.Indigo)
                Spacer(Modifier.width(4.dp))
                Text("Synchro", fontSize = 12.sp, color = VG.Indigo, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable { addDialog = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp), tint = VG.Cyan)
                Spacer(Modifier.width(4.dp))
                Text("Ajouter", fontSize = 12.sp, color = VG.Cyan, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (bookmarks.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    "Appuyez sur « Ajouter » ou sur l'étoile ★ dans la barre d'adresse pour enregistrer un site.",
                    Modifier.padding(16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(bookmarks, key = { _, b -> b.url }) { i, b ->
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.staggerIn(i)) {
                        GlassCard(
                            Modifier.width(96.dp).height(104.dp), tilt = true, glow = colorFor(b.url),
                            onClick = { open(b.url) },
                            onLongClick = { menu = true }
                        ) {
                            Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                val c = colorFor(b.url)
                                Box(
                                    Modifier.size(42.dp).clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(c.copy(alpha = 0.7f), c))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(b.title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(b.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Modifier (nom & lien)") }, onClick = { menu = false; editTarget = b })
                            DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; AppPrefs.removeBookmark(b.url) })
                        }
                    }
                }
            }
        }

        // ---- Sites récents ----
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Sites web récemment utilisés")
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        AppPrefs.clearHistory()
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(16.dp), tint = VG.Coral)
                    Spacer(Modifier.width(4.dp))
                    Text("Effacer", fontSize = 12.sp, color = VG.Coral)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val recent = history.distinctBy { AppPrefs.hostOf(it.url) }.take(5)
        if (recent.isEmpty()) {
            Text("Aucun site visité pour le moment.", Modifier.padding(horizontal = 24.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                recent.forEachIndexed { i, h ->
                    val host = AppPrefs.hostOf(h.url)
                    val c = colorFor(host)
                    Column(Modifier.width(58.dp).staggerIn(i), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(50.dp).glass(CircleShape, c, 8.dp).bounceClick { open(h.url) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(host.take(1).uppercase(), color = c, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(host.substringBeforeLast('.'), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---- Aide ----
        Spacer(Modifier.height(30.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            GradientButton("Comment télécharger", Modifier.fillMaxWidth(), Icons.Rounded.QuestionMark, onClick = onOpenHelp)
        }
    }

    if (addDialog) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Enregistrer un site") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nom du site") }, singleLine = true)
                    OutlinedTextField(
                        url, { url = it },
                        label = { Text("Lien (URL)") },
                        placeholder = { Text("https://exemple.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (url.isNotBlank()) {
                        AppPrefs.addBookmark(name, url)
                        addDialog = false
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Annuler") } }
        )
    }

    editTarget?.let { b ->
        var name by remember(b.url) { mutableStateOf(b.title) }
        var url by remember(b.url) { mutableStateOf(b.url) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Modifier le site") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nom du site") }, singleLine = true)
                    OutlinedTextField(
                        url, { url = it },
                        label = { Text("Nouveau lien (URL)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (url.isNotBlank()) AppPrefs.updateBookmark(b.url, name, url)
                    editTarget = null
                }) { Text("Mettre à jour") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun SuggestionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Barre pilule qui « morphe » au focus : élargissement, bordure lumineuse dégradée, ombre renforcée. */
@Composable
private fun SmartSearchBar(query: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val hPad by animateDpAsState(if (focused) 10.dp else 20.dp, label = "hpad")
    val elev by animateDpAsState(if (focused) 24.dp else 8.dp, label = "elev")
    val height by animateDpAsState(if (focused) 58.dp else 52.dp, label = "h")
    val glowAlpha by animateFloatAsState(if (focused) 0.8f else 0.25f, label = "glow")
    val iconTint by animateColorAsState(if (focused) VG.Cyan else MaterialTheme.colorScheme.onSurfaceVariant, label = "tint")
    val shape = RoundedCornerShape(50)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = hPad).height(height)
            .shadow(elev, shape, ambientColor = VG.Indigo.copy(alpha = glowAlpha), spotColor = VG.Cyan.copy(alpha = glowAlpha))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
            .border(if (focused) 1.5.dp else 1.dp, if (focused) VG.brand else glassBorderBrush(), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = iconTint)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = Brush.verticalGradient(listOf(VG.Cyan, VG.Indigo)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Rechercher ou inscrire l'URL", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    inner()
                }
            }
        )
        AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Close, "Effacer", Modifier.size(22.dp).bounceClick { onChange("") }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(34.dp).clip(CircleShape).background(VG.brand).bounceClick(onClick = onSubmit), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowForward, "Valider", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
