package com.vidgrab.app.ui.library

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.PlayerActivity
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.data.Format
import com.vidgrab.app.data.LibraryItem
import com.vidgrab.app.data.LibraryRepository
import com.vidgrab.app.ui.MainViewModel
import com.vidgrab.app.ui.components.GlassIconButton
import com.vidgrab.app.ui.components.ScreenTitle
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.components.glass
import com.vidgrab.app.ui.components.staggerIn
import com.vidgrab.app.ui.components.tilt3D
import com.vidgrab.app.ui.theme.VG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class PinFlow { NONE, UNLOCK, CREATE }

@Composable
fun LibraryScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pub by vm.library.collectAsStateWithLifecycle()
    val prv by vm.privateItems.collectAsStateWithLifecycle()
    val viewed by AppPrefs.viewed.collectAsStateWithLifecycle()

    var privateMode by remember { mutableStateOf(false) }
    var pinFlow by remember { mutableStateOf(PinFlow.NONE) }
    var pendingMove by remember { mutableStateOf<LibraryItem?>(null) }
    var sort by remember { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var renameItem by remember { mutableStateOf<LibraryItem?>(null) }
    var deleteItem by remember { mutableStateOf<LibraryItem?>(null) }

    LaunchedEffect(Unit) { vm.refreshAll() }
    LaunchedEffect(privateMode) { if (privateMode) vm.refreshPrivate() }

    val source = if (privateMode) prv else pub
    val items = remember(source, sort) {
        when (sort) {
            1 -> source.sortedBy { it.title.lowercase() }
            2 -> source.sortedByDescending { it.sizeBytes }
            else -> source.sortedByDescending { it.dateAdded }
        }
    }
    val listState = rememberLazyListState()

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun move(item: LibraryItem) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (item.isPrivate) LibraryRepository.moveToPublic(ctx, item) else LibraryRepository.moveToPrivate(ctx, item)
            }
            toast(if (ok) (if (item.isPrivate) "Vidéo restaurée dans la Galerie" else "Déplacée vers le dossier privé") else "Opération impossible")
            vm.refreshAll()
        }
    }

    fun play(item: LibraryItem) {
        AppPrefs.markViewed(item.key)
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java).setData(item.uri).putExtra("key", item.key).putExtra("title", item.title)
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                ScreenTitle("TERMINÉ")
                if (privateMode) Text("Dossier privé", color = VG.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            // Cadenas animé : pivote au déverrouillage
            val lockRot by animateFloatAsState(
                if (privateMode) -28f else 0f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "lock"
            )
            Box(
                Modifier.size(44.dp).glass(RoundedCornerShape(14.dp), if (privateMode) VG.Cyan else VG.Indigo, 8.dp).bounceClick {
                    if (privateMode) privateMode = false
                    else pinFlow = if (AppPrefs.hasPin()) PinFlow.UNLOCK else PinFlow.CREATE
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (privateMode) Icons.Rounded.LockOpen else Icons.Rounded.Lock, "Dossier privé",
                    Modifier.graphicsLayer { rotationZ = lockRot }, tint = if (privateMode) VG.Cyan else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(10.dp))
            Box {
                Box(
                    Modifier.size(44.dp).glass(RoundedCornerShape(14.dp), VG.Indigo, 8.dp).bounceClick { sortMenu = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Sort, "Trier") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("Plus récentes", "Nom (A → Z)", "Taille").forEachIndexed { i, label ->
                        DropdownMenuItem(
                            text = { Text(label, fontWeight = if (sort == i) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { sort = i; sortMenu = false }
                        )
                    }
                }
            }
        }

        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(bottom = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                Text(if (privateMode) "Dossier privé vide" else "Aucune vidéo téléchargée", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 130.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(items, key = { _, v -> v.key }) { i, item ->
                    Box(
                        Modifier.staggerIn(i).graphicsLayer {
                            // Profondeur au défilement : inclinaison + léger recul selon la distance au centre
                            val info = listState.layoutInfo
                            val li = info.visibleItemsInfo.firstOrNull { it.key == item.key }
                            if (li != null) {
                                val vh: Float = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1).toFloat()
                                val center: Float = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                val ic: Float = li.offset + li.size / 2f
                                val d: Float = ((ic - center) / vh).coerceIn(-1f, 1f)
                                rotationX = d * 9f
                                val s: Float = 1f - abs(d) * 0.07f
                                scaleX = s
                                scaleY = s
                                cameraDistance = 16f * density
                            }
                        }
                    ) {
                        VideoCard(
                            item, isNew = item.key !in viewed,
                            onPlay = { play(item) },
                            onRename = { renameItem = item },
                            onShare = { LibraryRepository.share(ctx, item) },
                            onMove = {
                                if (item.isPrivate) move(item)
                                else if (!AppPrefs.hasPin()) { pendingMove = item; pinFlow = PinFlow.CREATE }
                                else move(item)
                            },
                            onDelete = { deleteItem = item }
                        )
                    }
                }
            }
        }
    }

    // ---- Dialogues ----
    when (pinFlow) {
        PinFlow.UNLOCK -> PinDialog(PinMode.VERIFY, onSuccess = { pinFlow = PinFlow.NONE; privateMode = true }, onDismiss = { pinFlow = PinFlow.NONE })
        PinFlow.CREATE -> PinDialog(PinMode.CREATE, onSuccess = { pin ->
            AppPrefs.setPin(pin)
            pinFlow = PinFlow.NONE
            val m = pendingMove
            if (m != null) { pendingMove = null; move(m) } else privateMode = true
        }, onDismiss = { pinFlow = PinFlow.NONE; pendingMove = null })
        PinFlow.NONE -> Unit
    }

    renameItem?.let { item ->
        var name by remember(item.key) { mutableStateOf(item.title) }
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { Text("Renommer la vidéo") },
            text = { OutlinedTextField(name, { name = it }, singleLine = false, maxLines = 3) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) scope.launch {
                        val ok = withContext(Dispatchers.IO) { LibraryRepository.rename(ctx, item, name.trim()) }
                        if (!ok) toast("Renommage impossible")
                        vm.refreshAll()
                    }
                    renameItem = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { renameItem = null }) { Text("Annuler") } }
        )
    }

    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text("Supprimer cette vidéo ?") },
            text = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { LibraryRepository.delete(ctx, item) }
                        if (!ok) toast("Suppression impossible")
                        vm.refreshAll()
                    }
                    deleteItem = null
                }) { Text("Supprimer", color = VG.Coral) }
            },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun VideoCard(
    item: LibraryItem, isNew: Boolean,
    onPlay: () -> Unit, onRename: () -> Unit, onShare: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(24.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val zoom by animateFloatAsState(
        if (pressed) 1.08f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "zoom"
    )
    val thumb by produceState<ImageBitmap?>(null, item.key) {
        value = withContext(Dispatchers.IO) { LibraryRepository.thumbnail(ctx, item)?.asImageBitmap() }
    }
    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().tilt3D(shape).glass(shape, if (isNew) VG.Coral else VG.Indigo, 14.dp)
            .clickable(interactionSource = source, indication = null, onClick = onPlay)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            val t = thumb
            if (t != null) {
                Image(t, null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }, contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(VG.Indigo.copy(alpha = 0.4f), VG.Cyan.copy(alpha = 0.3f)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(44.dp), tint = Color.White.copy(alpha = 0.8f))
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            if (isNew) {
                Text(
                    "NOUVEAU", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        .shadow(10.dp, RoundedCornerShape(8.dp), ambientColor = VG.Coral, spotColor = VG.Coral)
                        .background(VG.alert, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            if (item.durationMs > 0L) {
                Text(
                    Format.duration(item.durationMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(Format.size(item.sizeBytes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                GlassIconButton(Icons.Rounded.MoreVert, "Actions") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, text = { Text("Lire") }, onClick = { menu = false; onPlay() })
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Edit, null) }, text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                    if (!item.isPrivate) {
                        DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Share, null) }, text = { Text("Partager") }, onClick = { menu = false; onShare() })
                    }
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) },
                        text = { Text(if (item.isPrivate) "Sortir du dossier privé" else "Déplacer vers le dossier privé") },
                        onClick = { menu = false; onMove() }
                    )
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = VG.Coral) }, text = { Text("Supprimer", color = VG.Coral) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
