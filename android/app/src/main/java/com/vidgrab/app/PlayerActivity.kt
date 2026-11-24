package com.vidgrab.app

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.ui.player.PlayerScreen
import com.vidgrab.app.ui.theme.VidGrabTheme

/** Lecteur plein écran Media3 ExoPlayer avec support Picture-in-Picture (PiP). */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var inPipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        intent.getStringExtra("key")?.let { AppPrefs.markViewed(it) }
        val title = intent.getStringExtra("title").orEmpty()

        val p = ExoPlayer.Builder(this).build()
        player = p
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersive()

        setContent {
            VidGrabTheme("dark") {
                PlayerScreen(
                    player = p,
                    title = title,
                    inPip = inPipMode,
                    onBack = { finish() },
                    onToggleOrientation = { toggleOrientation() },
                    onEnterPip = { enterPip() }
                )
            }
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val ratio = player?.videoSize?.let { vs ->
            if (vs.width > 0 && vs.height > 0) {
                val r = vs.width.toFloat() / vs.height.toFloat()
                if (r in 0.42f..2.39f) Rational(vs.width, vs.height) else Rational(16, 9)
            } else Rational(16, 9)
        } ?: Rational(16, 9)

        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(player?.isPlaying == true)
        }
        return builder.build()
    }

    private fun enterPip() {
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !isInPictureInPictureMode) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode) enterImmersive()
    }

    private fun enterImmersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !inPipMode) enterImmersive()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
