package `is`.xyz.mpv

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

// P2 (JAV 메타): 영상 상세화면 — 임베드 커버 + 품번/제목 + 배우/시리즈/스튜디오/장르/날짜
//   + 기술정보(해상도/길이/크기/경로). MMR 로 컨테이너 태그 추출. 길게 누르면 진입.
class VideoDetailActivity : AppCompatActivity() {

    private lateinit var uriStr: String
    private var fallbackName = ""
    private var pendingResume = false
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            Playback.onResult(this, uriStr, res.data)
            updateResumeButton()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_detail)

        uriStr = intent.getStringExtra("uri") ?: ""
        fallbackName = intent.getStringExtra("name") ?: ""

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = "정보"
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btn_play).setOnClickListener {
            playLauncher.launch(Playback.intentFor(this, uriStr, fallbackName, resume = false))
        }
        findViewById<MaterialButton>(R.id.btn_resume).setOnClickListener {
            playLauncher.launch(Playback.intentFor(this, uriStr, fallbackName, resume = true))
        }
        updateResumeButton()

        load()
    }

    override fun onResume() {
        super.onResume()
        updateResumeButton()
    }

    private fun updateResumeButton() {
        val btn = findViewById<MaterialButton>(R.id.btn_resume)
        val pr = Progress.get(this, uriStr)
        if (pr != null && pr.first > 3000 && pr.first < pr.second - 3000) {
            btn.visibility = View.VISIBLE
            btn.text = "이어보기 (${MediaLibrary.fmtDur(pr.first)})"
        } else {
            btn.visibility = View.GONE
        }
    }

    private fun load() {
        val uri = Uri.parse(uriStr)
        Thread {
            val mmr = MediaMetadataRetriever()
            var cover: android.graphics.Bitmap? = null
            val m = HashMap<String, String?>()
            var w = 0; var h = 0; var durMs = 0L
            try {
                mmr.setDataSource(this, uri)
                fun k(id: Int) = mmr.extractMetadata(id)?.trim().takeIf { !it.isNullOrEmpty() }
                m["title"] = k(MediaMetadataRetriever.METADATA_KEY_TITLE)
                m["artist"] = k(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                m["album"] = k(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                m["albumartist"] = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                m["genre"] = k(MediaMetadataRetriever.METADATA_KEY_GENRE)
                m["date"] = k(MediaMetadataRetriever.METADATA_KEY_DATE)
                w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                durMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mmr.embeddedPicture?.let { cover = BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (_: Throwable) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
            val fb = cover; val fm = m; val fw = w; val fh = h; val fd = durMs
            runOnUiThread { if (!isFinishing) bind(fb, fm, fw, fh, fd) }
        }.start()
    }

    private fun bind(cover: android.graphics.Bitmap?, m: Map<String, String?>, w: Int, h: Int, durMs: Long) {
        cover?.let { findViewById<android.widget.ImageView>(R.id.cover).setImageBitmap(it) }

        // 품번/제목 — 임베드 title 우선(품번 중복 제거), 없으면 파일명
        val raw = m["title"]
        val codeView = findViewById<TextView>(R.id.code)
        val titleView = findViewById<TextView>(R.id.title)
        if (!raw.isNullOrEmpty()) {
            val parts = raw.split(Regex("\\s+"), limit = 2)
            var code = parts[0]
            var t = if (parts.size == 2) parts[1].trim() else ""
            while (t.isNotEmpty() && t.startsWith(code)) t = t.removePrefix(code).trimStart()
            codeView.text = code
            titleView.text = t
            titleView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
            findViewById<MaterialToolbar>(R.id.toolbar).title = code
        } else {
            codeView.text = fallbackName
            titleView.visibility = View.GONE
        }

        val box = findViewById<LinearLayout>(R.id.meta_container)
        box.removeAllViews()
        addRow(box, "배우", m["artist"])
        addRow(box, "시리즈", m["album"])
        addRow(box, "스튜디오", m["albumartist"])
        addRow(box, "장르", m["genre"])
        addRow(box, "날짜", m["date"])
        addRow(box, "길이", if (durMs > 0) MediaLibrary.fmtDur(durMs) else null)
        addRow(box, "해상도", if (w > 0 && h > 0) "${w}×${h}" else null)
        addRow(box, "경로", if (uriStr.startsWith("content://")) null else uriStr)
    }

    private fun addRow(box: LinearLayout, label: String, value: String?) {
        if (value.isNullOrEmpty()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val l = TextView(this).apply {
            text = label
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 13f
            width = dp(64)
        }
        val v = TextView(this).apply {
            text = value
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        l.gravity = Gravity.TOP
        row.addView(l)
        row.addView(v)
        box.addView(row)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun open(ctx: Context, uri: String, name: String) {
            ctx.startActivity(
                Intent(ctx, VideoDetailActivity::class.java)
                    .putExtra("uri", uri)
                    .putExtra("name", name)
            )
        }
    }
}
