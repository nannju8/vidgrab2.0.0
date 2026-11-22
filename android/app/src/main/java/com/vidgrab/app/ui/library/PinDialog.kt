package com.vidgrab.app.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.ui.components.bounceClick
import com.vidgrab.app.ui.components.glass
import com.vidgrab.app.ui.theme.VG
import kotlinx.coroutines.launch

enum class PinMode { VERIFY, CREATE }

/** Pavé numérique tactile : vérification ou création (saisie + confirmation) du code PIN à 4 chiffres. */
@Composable
fun PinDialog(mode: PinMode, onSuccess: (String) -> Unit, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(0f) }
    var step by remember { mutableIntStateOf(0) }

    fun fail(msg: String) {
        error = msg
        pin = ""
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            for (v in listOf(-18f, 18f, -12f, 12f, -6f, 0f)) shake.animateTo(v, tween(50))
        }
    }

    fun submit() {
        when (mode) {
            PinMode.VERIFY -> if (AppPrefs.verifyPin(pin)) onSuccess(pin) else fail("Code incorrect")
            PinMode.CREATE -> {
                if (first == null) {
                    first = pin
                    pin = ""
                    step = 1
                    error = null
                } else if (first == pin) onSuccess(pin)
                else {
                    first = null
                    step = 0
                    fail("Les codes ne correspondent pas")
                }
            }
        }
    }

    fun press(d: String) {
        if (pin.length >= 4) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        pin += d
        error = null
        if (pin.length == 4) submit()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), VG.Indigo, 24.dp, alpha = 0.97f).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(52.dp).background(VG.brand, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, null, tint = Color.White)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    mode == PinMode.VERIFY -> "Dossier privé"
                    step == 0 -> "Créer un code PIN"
                    else -> "Confirmez le code"
                },
                fontWeight = FontWeight.Black, fontSize = 18.sp
            )
            Text(
                error ?: if (mode == PinMode.VERIFY) "Saisissez votre code à 4 chiffres" else "4 chiffres pour protéger vos vidéos",
                color = if (error != null) VG.Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.offset(x = shake.value.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    val filled = i < pin.length
                    Box(
                        Modifier.size(16.dp)
                            .background(if (filled) VG.brand else androidx.compose.ui.graphics.SolidColor(Color.Transparent), CircleShape)
                            .border(1.5.dp, if (filled) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { k ->
                        if (k.isEmpty()) Spacer(Modifier.size(64.dp))
                        else Box(
                            Modifier.size(64.dp).glass(CircleShape, VG.Cyan, 6.dp)
                                .bounceClick { if (k == "⌫") { if (pin.isNotEmpty()) pin = pin.dropLast(1) } else press(k) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (k == "⌫") Icon(Icons.Rounded.Backspace, null, Modifier.size(22.dp))
                            else Text(k, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
