package com.vidgrab.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.ui.MainScreen
import com.vidgrab.app.ui.theme.LocalDarkTheme
import com.vidgrab.app.ui.theme.VidGrabTheme
import com.vidgrab.app.web.TabManager

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* refus toléré : le service reste actif */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TabManager.attachActivity(this)

        // POST_NOTIFICATIONS : demande runtime sur Android 13+
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val mode by AppPrefs.themeMode.collectAsStateWithLifecycle()
            VidGrabTheme(mode) {
                val dark = LocalDarkTheme.current
                DisposableEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose { }
                }
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TabManager.attachActivity(this)
    }

    override fun onDestroy() {
        // Remet applicationContext dans les WebView (aucune fuite lors des rotations / recréations)
        TabManager.detachActivity(this)
        super.onDestroy()
    }
}
