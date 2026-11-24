package com.kuryuk.jetbrowser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject

/** TV'de telefondan gönderilen videoyu tam ekran oynatır. */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        view = PlayerView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            setShowNextButton(false)
            setShowPreviousButton(false)
            keepScreenOn = true
        }
        setContentView(view)
        play(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        play(intent)
    }

    private fun play(i: Intent?) {
        val url = i?.getStringExtra("url") ?: return finish()
        val type = i.getStringExtra("type") ?: ""
        val headers = HashMap<String, String>()
        try {
            val o = JSONObject(i.getStringExtra("headers") ?: "{}")
            o.keys().forEach { headers[it] = o.getString(it) }
        } catch (_: Exception) { }
        val ua = headers.remove("User-Agent")
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setDefaultRequestProperties(headers)
        if (ua != null) http.setUserAgent(ua)

        player?.release()
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        player = p
        view.player = p
        val mime = when (type) {
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        p.setMediaItem(MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build())
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Video oynatılamadı", Toast.LENGTH_LONG).show()
                Hub.server?.send("N TV videoyu oynatamadı. Listeden başka bir video seçin ya da sayfayı açın")
            }
        })
        val pos = i.getLongExtra("pos", 0L)
        if (pos > 5000) p.seekTo(pos - 3000)
        p.prepare()
        p.playWhenReady = true
    }

    /** Telefon kumandasından gelen komutlar; true = burada işlendi. */
    private fun onRemote(line: String): Boolean {
        val p = player ?: return false
        when (line) {
            "C", "K CENTER", "K ENTER" -> { if (p.isPlaying) p.pause() else p.play(); view.showController() }
            "K LEFT" -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); view.showController() }
            "K RIGHT" -> { p.seekTo(p.currentPosition + 10_000); view.showController() }
            "K UP", "K DOWN", "K MENU" -> view.showController()
            "K BACK" -> finish()
            else -> return line.startsWith("M ") || line.startsWith("S") || line == "LD" || line == "LU"
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        Hub.player = ::onRemote
    }

    override fun onPause() {
        super.onPause()
        Hub.player = null
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
