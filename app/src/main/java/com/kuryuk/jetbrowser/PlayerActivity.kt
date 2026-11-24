package com.kuryuk.jetbrowser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/** TV'de telefondan gönderilen videoyu (ve altyazılarını) tam ekran oynatır. */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    /** Her yeni gönderimde artar; geç kalan eski hazırlıklar yok sayılır. */
    @Volatile private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // bekleme modundan gönderilen video için ekranı aç
        if (android.os.Build.VERSION.SDK_INT >= 27) { setTurnScreenOn(true); setShowWhenLocked(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        view = PlayerView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowSubtitleButton(true)
            keepScreenOn = true
        }
        setContentView(view)
        play(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        play(intent)
    }

    private class Sub(val url: String, val label: String, val turkish: Boolean,
                      val text: String?, val headers: Map<String, String>)

    /** Yeni videoyu oynat (açıkken gelen yeni video öncekinin yerini alır). */
    fun play(i: Intent?) {
        val url = i?.getStringExtra("url") ?: return finish()
        val type = i.getStringExtra("type") ?: ""
        val pos = i.getLongExtra("pos", 0L)
        val headers = HashMap<String, String>()
        try {
            val o = JSONObject(i.getStringExtra("headers") ?: "{}")
            o.keys().forEach { headers[it] = o.getString(it) }
        } catch (_: Exception) { }
        val subs = ArrayList<Sub>()
        try {
            val a = JSONArray(i.getStringExtra("subsFile")?.let { File(it).readText() } ?: i.getStringExtra("subs") ?: "[]")
            for (k in 0 until a.length()) {
                val s = a.getJSONObject(k)
                val h = HashMap<String, String>()
                s.optJSONObject("h")?.let { o -> o.keys().forEach { h[it] = o.getString(it) } }
                subs.add(Sub(s.getString("u"), s.optString("l"), s.optBoolean("tr"),
                    if (s.has("t")) s.getString("t") else null, h))
            }
        } catch (_: Exception) { }

        val gen = ++generation
        player?.stop()
        if (subs.isNotEmpty()) Toast.makeText(this, "Altyazı hazırlanıyor…", Toast.LENGTH_SHORT).show()
        // altyazıları TV'nin kendisi indirir ve Türkçe karakterleri düzeltir, sonra oynatma başlar
        Thread {
            val seen = HashSet<Int>()
            val files = subs.mapIndexedNotNull { k, s -> fetchSub(s, headers, k)?.let { Pair(s, it) } }
                .filter { seen.add(it.second.readText().hashCode()) }
            if (subs.isNotEmpty() && files.isEmpty()) note("TV altyazıları açamadı (${subs.size} dosya geldi)")
            runOnUiThread { if (gen == generation && !isFinishing) start(url, type, pos, headers, files) }
        }.start()
    }

    private fun start(url: String, type: String, pos: Long, headers: Map<String, String>, subs: List<Pair<Sub, File>>) {
        val h = HashMap(headers)
        val ua = h.remove("User-Agent")
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setDefaultRequestProperties(h)
        if (ua != null) http.setUserAgent(ua)
        val ds = DefaultDataSource.Factory(this, http)

        player?.release()
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("tr")
            .setPreferredAudioLanguage("tr")
            .build()
        player = p
        view.player = p
        applySubStyle()

        val mime = when (type) {
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        // dili altyazının içeriğinden bul; aynı dilden birden fazlaysa numaralandır
        val langs = subs.map { (s, f) -> detectLang(f.readText()) ?: if (s.turkish) "tr" else null }
        val seenLang = HashMap<String?, Int>()
        val labels = langs.mapIndexed { k, l ->
            val n = (seenLang[l] ?: 0) + 1
            seenLang[l] = n
            val name = LANG_NAMES[l] ?: subs[k].first.label.takeIf { it.isNotBlank() && it.length < 30 } ?: "Altyazı"
            if (n > 1) "$name $n" else name
        }
        val first = langs.indexOf("tr").let { if (it < 0) 0 else it }
        val subCfg = subs.mapIndexed { k, (s, f) ->
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(if (f.name.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(langs[k])
                .setLabel(labels[k])
                .setSelectionFlags(if (k == first) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        p.setMediaItem(MediaItem.Builder().setUri(url)
            .apply { if (mime != null) setMimeType(mime) }
            .setSubtitleConfigurations(subCfg)
            .build())
        p.addListener(object : Player.Listener {
            private var checked = false
            override fun onTracksChanged(tracks: Tracks) {
                if (checked || tracks.groups.isEmpty()) return
                checked = true
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (text.isEmpty()) {
                    if (subs.isNotEmpty()) note("Altyazı dosyaları geldi ama oynatıcı okuyamadı")
                    return
                }
                // bizim eklediklerimizi (adı olan) videonun kendi boş kanalına tercih et; Türkçe önce
                val ours = text.filter { it.getTrackFormat(0).label != null }
                val sel = text.filter { it.isSelected }
                if (sel.isEmpty() || (ours.isNotEmpty() && sel.none { it in ours })) {
                    val pool = ours.ifEmpty { text }
                    val g = pool.firstOrNull { it.getTrackFormat(0).language == "tr" } ?: pool[0]
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
                        .build()
                }
                if (ours.isEmpty()) note("Gönderilen altyazılar açılamadı; videonun kendi altyazısı denendi")
                else note("Altyazı açık ✓ (${ours.size} seçenek, CC tuşundan değiştirilebilir)")
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Video oynatılamadı", Toast.LENGTH_LONG).show()
                Hub.server?.send("N TV videoyu oynatamadı. Listeden başka bir video seçin ya da sayfayı açın")
            }
        })
        if (pos > 5000) p.seekTo(pos - 3000)
        p.prepare()
        p.playWhenReady = true
    }

    /** Altyazıyı indirir; UTF-8 değilse Türkçe kodlamadan (Windows-1254) çevirir. */
    private fun fetchSub(s: Sub, headers: Map<String, String>, k: Int): File? = try {
        val text = s.text ?: run {
        val c = URL(s.url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = true
        (s.headers.ifEmpty { headers }).forEach { (a, b) -> c.setRequestProperty(a, b) }
        val bytes = c.inputStream.use { it.readBytes() }
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { String(bytes, Charset.forName("windows-1254")) }
        }
        val clean = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val vtt = clean.startsWith("WEBVTT")
        val head = clean.take(4000)
        val timed = Regex("(\\d+:)?\\d\\d:\\d\\d[,.]\\d+\\s*-->").containsMatchIn(head)
        // ilerleme çubuğu küçük resimleri (thumbnails.vtt) altyazı değildir
        val thumbs = Regex("\\.(jpe?g|png|webp)(#xywh|\\s|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(head)
        if (!timed || thumbs) null
        else File(cacheDir, "sub_$k" + if (vtt) ".vtt" else ".srt").apply { writeText(clean) }
    } catch (_: Exception) { null }

    /** Altyazının dilini metindeki sık kelimelere ve harflere bakarak tahmin eder. */
    private fun detectLang(raw: String): String? {
        val text = raw.lineSequence()
            .filter { !it.contains("-->") && !it.startsWith("WEBVTT") && it.isNotBlank() && it.trim().toIntOrNull() == null }
            .take(400).joinToString(" ")
            .replace(Regex("<[^>]*>|\\{[^}]*\\}"), " ")
        if (text.length < 40) return null
        if (text.count { it in '\u0400'..'\u04FF' } > 30) return "ru"
        if (text.count { it in '\u0600'..'\u06FF' } > 30) return "ar"
        val words = text.lowercase(java.util.Locale("tr")).split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        val score = HashMap<String, Int>()
        for ((lang, set) in COMMON) score[lang] = words.count { it in set }
        score["tr"] = (score["tr"] ?: 0) + text.count { it in "ğşıİĞŞ" }
        val best = score.maxByOrNull { it.value } ?: return null
        return if (best.value >= 8) best.key else null
    }

    /** Ayarlardaki altyazı rengi, boyutu, yüksekliği ve arka planı. */
    private fun applySubStyle() {
        val st = Store(this)
        val sv = view.subtitleView ?: return
        val fg = Store.SUB_COLOR_VALUES[st.subColor.coerceIn(0, 3)]
        val bg = when (st.subBg) { 1 -> 0x99000000.toInt(); 2 -> 0xFF000000.toInt(); else -> 0 }
        sv.setApplyEmbeddedStyles(false)
        sv.setApplyEmbeddedFontSizes(false)
        sv.setStyle(androidx.media3.ui.CaptionStyleCompat(fg, bg, 0,
            if (st.subBg == 0) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
            0xFF000000.toInt(), null))
        sv.setFractionalTextSize(Store.SUB_SIZE_VALUES[st.subSize.coerceIn(0, 3)])
        sv.setBottomPaddingFraction(Store.SUB_POS_VALUES[st.subPos.coerceIn(0, 2)])
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySubStyle()   // ayarlar değiştiyse geri dönünce uygula
    }

    /** Hem TV'de hem telefonda kısa bilgi gösterir. */
    private fun note(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        Hub.server?.send("N $msg")
    }

    /** Telefon kumandasından gelen komutlar; true = burada işlendi. */
    private fun onRemote(line: String): Boolean {
        val p = player ?: return false
        when (line) {
            "C", "K CENTER", "K ENTER" -> { if (p.isPlaying) p.pause() else p.play(); view.showController() }
            "K LEFT" -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); view.showController() }
            "K RIGHT" -> { p.seekTo(p.currentPosition + 10_000); view.showController() }
            "K UP", "K DOWN", "K MENU" -> view.showController()
            "K BACK" -> backPressed()
            else -> return line.startsWith("M ") || line == "SD" || line == "SE" || line.startsWith("S ") ||
                line == "LD" || line == "LU"
        }
        return true
    }

    /** Geri: kontroller açıksa önce onları gizle, değilse oynatıcıyı kapat. */
    private fun backPressed() {
        if (view.isControllerFullyVisible) view.hideController() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = backPressed()

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
        if (instance === this) instance = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: PlayerActivity? = null

        private val LANG_NAMES = mapOf(
            "tr" to "Türkçe", "en" to "İngilizce", "de" to "Almanca", "fr" to "Fransızca",
            "es" to "İspanyolca", "it" to "İtalyanca", "ru" to "Rusça", "ar" to "Arapça"
        )

        private val COMMON = mapOf(
            "tr" to setOf("ve", "bir", "bu", "ne", "için", "ama", "değil", "ben", "sen", "mi", "mı", "çok", "var", "yok",
                "şey", "evet", "hayır", "da", "de", "beni", "seni", "neden", "nasıl", "burada", "şimdi"),
            "en" to setOf("the", "you", "and", "is", "to", "it", "that", "what", "this", "not", "i'm", "don't", "we", "are", "have"),
            "de" to setOf("der", "die", "und", "ist", "nicht", "ich", "das", "du", "wir", "sie", "ein", "es", "was"),
            "fr" to setOf("le", "et", "est", "je", "pas", "vous", "que", "les", "une", "c'est", "nous", "il", "ce"),
            "es" to setOf("el", "que", "y", "no", "es", "lo", "los", "una", "por", "qué", "pero", "está", "yo"),
            "it" to setOf("il", "che", "non", "è", "di", "un", "una", "per", "sono", "io", "ma", "cosa", "questo")
        )
    }
}
