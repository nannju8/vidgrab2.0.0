package com.vidgrab.app.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vidgrab.app.data.BatchConfig
import com.vidgrab.app.ui.components.GradientButton
import com.vidgrab.app.ui.components.glass
import com.vidgrab.app.ui.theme.VG
import com.vidgrab.app.web.BatchUrlPattern
import com.vidgrab.app.web.TabState

@Composable
fun BatchSetupDialog(
    tab: TabState,
    initialQuality: String = "Meilleure",
    onStart: (BatchConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val matches = remember(tab.url) { BatchUrlPattern.findNumbers(tab.url) }
    val bestIdx = remember(matches) {
        if (matches.isEmpty()) -1 else matches.indices.maxByOrNull { matches[it].score } ?: 0
    }

    var selectedIdx by remember { mutableIntStateOf(bestIdx) }
    var urlTemplate by remember {
        mutableStateOf(
            if (bestIdx >= 0) BatchUrlPattern.applyPlaceholder(tab.url, matches[bestIdx])
            else tab.url
        )
    }
    var seriesTitle by remember { mutableStateOf(BatchUrlPattern.cleanSeriesTitle(tab.title)) }
    var startText by remember { mutableStateOf(if (bestIdx >= 0) matches[bestIdx].value.toString() else "1") }
    var endText by remember {
        val s = if (bestIdx >= 0) matches[bestIdx].value else 1
        mutableStateOf((s + 11).toString())
    }
    var detectedPad by remember { mutableIntStateOf(if (bestIdx >= 0) matches[bestIdx].padWidth else 1) }
    var zeroPad by remember { mutableStateOf(detectedPad > 1) }
    var fastMode by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(initialQuality) }
    var playersText by remember {
        val base = listOfNotNull(tab.lastClickedPlayer.takeIf { it.isNotBlank() }, "voe", "vidmoly", "doodstream", "filemoon", "sibnet")
            .distinctBy { it.lowercase() }
        mutableStateOf(base.joinToString(", "))
    }

    val startNum = startText.toIntOrNull() ?: 1
    val endNum = endText.toIntOrNull() ?: startNum
    val padWidth = if (zeroPad) maxOf(detectedPad, 2) else 1
    val valid = urlTemplate.contains("{n}") && endNum >= startNum
    val count = if (valid) (endNum - startNum + 1) else 0

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(28.dp), VG.Indigo, 24.dp, alpha = 0.96f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                "Mode Série (Lots)",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, brush = VG.brand)
            )
            Text(
                "Tourne dans un onglet Robot dédié : vous pouvez naviguer ailleurs pendant la détection.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (matches.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Quel nombre est le numéro d'épisode ?", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    matches.forEachIndexed { i, m ->
                        FilterChip(
                            selected = selectedIdx == i,
                            onClick = {
                                selectedIdx = i
                                urlTemplate = BatchUrlPattern.applyPlaceholder(tab.url, m)
                                startText = m.value.toString()
                                endText = (m.value + 11).toString()
                                detectedPad = m.padWidth
                                zeroPad = m.padWidth > 1
                            },
                            label = { Text(m.raw) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlTemplate,
                onValueChange = { urlTemplate = it },
                label = { Text("Modèle d'adresse ({n})") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = seriesTitle,
                onValueChange = { seriesTitle = it },
                label = { Text("Nom de la série") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.filter(Char::isDigit) },
                    label = { Text("Ép. début") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it.filter(Char::isDigit) },
                    label = { Text("Ép. fin") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().clickable { zeroPad = !zeroPad },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zéros devant (01, 02…)", Modifier.weight(1f), fontSize = 13.sp)
                Switch(checked = zeroPad, onCheckedChange = { zeroPad = it })
            }

            Row(
                Modifier.fillMaxWidth().clickable { fastMode = !fastMode },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Mode rapide (tout détecter maintenant)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (fastMode) "Enchaîne toute la série en ~30 s, puis vous pouvez quitter l'app."
                        else "Attend une place libre avant de détecter l'épisode suivant.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = fastMode, onCheckedChange = { fastMode = it })
            }

            Spacer(Modifier.height(6.dp))
            Text("Qualité préférée", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Meilleure", "1080p", "720p", "480p").forEach { q ->
                    FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q) })
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = playersText,
                onValueChange = { playersText = it },
                label = { Text("Ordre des lecteurs préférés") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (valid) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(VG.Cyan.copy(alpha = 0.12f)).padding(10.dp)
                ) {
                    Column {
                        Text("$count épisode(s) : Ép. $startNum → Ép. $endNum", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VG.Cyan)
                        Text(BatchUrlPattern.buildUrl(urlTemplate, startNum, padWidth), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (count > 1) {
                            Text("→ " + BatchUrlPattern.buildUrl(urlTemplate, endNum, padWidth), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Annuler") }
                Spacer(Modifier.weight(1f))
                GradientButton(
                    text = if (valid) "Lancer ($count)" else "Motif {n} requis",
                    onClick = {
                        if (valid) {
                            val cfg = BatchConfig(
                                tabId = tab.id,
                                urlTemplate = urlTemplate.trim(),
                                seriesTitle = seriesTitle.trim().ifBlank { "Série" },
                                startEp = startNum,
                                endEp = endNum,
                                padWidth = padWidth,
                                targetQuality = quality,
                                preferredPlayers = playersText.split(',').map { it.trim() }.filter { it.isNotBlank() },
                                fastMode = fastMode
                            )
                            onStart(cfg)
                        }
                    }
                )
            }
        }
    }
}
