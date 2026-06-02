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
        AuroraDrawable.apply(this)

        uriStr = intent.getStringExtra("uri") ?: ""
        fallbackName = intent.getStringExtra("name") ?: ""

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.detail_info)
        toolbar.setNavigationOnClickListener { finish() }

        setupFavRating()

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

    private val stars by lazy {
        listOf(R.id.star_1, R.id.star_2, R.id.star_3, R.id.star_4, R.id.star_5)
            .map { findViewById<android.widget.ImageView>(it) }
    }

    private fun setupFavRating() {
        val fav = findViewById<android.widget.ImageView>(R.id.fav_btn)
        fav.setOnClickListener {
            Favorites.toggle(this, uriStr)
            paintFav(fav)
        }
        paintFav(fav)
        stars.forEachIndexed { i, iv ->
            iv.setOnClickListener {
                val want = i + 1
                // 같은 별을 다시 누르면 해제
                Ratings.set(this, uriStr, if (Ratings.get(this, uriStr) == want) 0 else want)
                paintStars()
            }
        }
        paintStars()
    }

    private fun paintFav(fav: android.widget.ImageView) {
        fav.setImageResource(if (Favorites.has(this, uriStr)) R.drawable.ic_fav else R.drawable.ic_fav_border)
    }

    private fun paintStars() {
        val r = Ratings.get(this, uriStr)
        stars.forEachIndexed { i, iv ->
            iv.setImageResource(if (i < r) R.drawable.ic_star else R.drawable.ic_star_border)
        }
    }

    private fun updateResumeButton() {
        val btn = findViewById<MaterialButton>(R.id.btn_resume)
        val pr = Progress.get(this, uriStr)
        if (pr != null && pr.first > 3000 && pr.first < pr.second - 3000) {
            btn.visibility = View.VISIBLE
            btn.text = getString(R.string.detail_resume_at, MediaLibrary.fmtDur(pr.first))
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
                // 날짜는 아래 Mp4Tags.releaseDate(ilst ©day)로 — MMR date 는 컨테이너시간(1904).
                w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                durMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mmr.embeddedPicture?.let { cover = BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (_: Throwable) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
            val desc = Mp4Tags.description(this, uri)  // MMR 미노출 줄거리 — atom 직접 파싱
            m["date"] = Mp4Tags.releaseDate(this, uri) // ilst ©day(릴리스 연도), MMR date 회피
            val fb = cover; val fm = m; val fw = w; val fh = h; val fd = durMs
            runOnUiThread { if (!isFinishing) bind(fb, fm, fw, fh, fd, desc) }
        }.start()
    }

    private fun bind(cover: android.graphics.Bitmap?, m: Map<String, String?>, w: Int, h: Int, durMs: Long, desc: String?) {
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
            fetchHub(code)  // B-56 pull-display: 허브 통합 상태 비동기 추가
        } else {
            codeView.text = fallbackName
            titleView.visibility = View.GONE
        }

        val box = findViewById<LinearLayout>(R.id.meta_container)
        box.removeAllViews()
        // 이모지 표지(context_core.md §1 사용자 표시 규약) — 라벨 빠른 식별.
        addRow(box, getString(R.string.detail_actress), m["artist"])
        addRow(box, getString(R.string.detail_series), m["album"])
        addRow(box, getString(R.string.detail_studio), m["albumartist"])
        addRow(box, getString(R.string.detail_genre), m["genre"])
        addRow(box, getString(R.string.detail_date), m["date"])
        addRow(box, getString(R.string.detail_duration), if (durMs > 0) MediaLibrary.fmtDur(durMs) else null)
        addRow(box, getString(R.string.detail_resolution), if (w > 0 && h > 0) "${w}×${h}" else null)
        addRow(box, getString(R.string.detail_path), if (uriStr.startsWith("content://")) null else uriStr)
        if (!desc.isNullOrEmpty()) addParagraph(box, getString(R.string.detail_desc), desc)
    }

    // B-56 pull-display: 허브 GET /library → 임베드와 겹치지 않는 cross-system 상태 섹션.
    //   카탈로그(브라우저가 봄/요청함)·다운로드 생애주기. 허브 오프라인/미기록이면 조용히 생략.
    private fun fetchHub(code: String) {
        Library.fetch(this, code) { rec ->
            rec ?: return@fetch
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val cat = hubCatLabel(rec.optString("cat_status"))
                val dl = hubDlLabel(rec.optString("dl_status"))
                if (cat == null && dl == null) return@runOnUiThread
                val box = findViewById<LinearLayout>(R.id.meta_container)
                box.addView(TextView(this).apply {
                    text = getString(R.string.detail_hub)
                    setTextColor(0xFF80CBC4.toInt())   // 청록 — 임베드 메타와 구분
                    textSize = 13f
                    setPadding(0, dp(16), 0, dp(4))
                })
                addRow(box, getString(R.string.detail_catalog), cat)
                addRow(box, getString(R.string.detail_download), dl)
            }
        }
    }

    private fun hubCatLabel(s: String?): String? = when (s) {
        "visited" -> getString(R.string.cat_visited)
        "requested" -> getString(R.string.cat_requested)
        "hasAds" -> getString(R.string.cat_hasads)
        "failed" -> getString(R.string.cat_failed)
        "uncatalogued", "", null -> null   // 브라우저 기록 없음 → 굳이 표시 안 함
        else -> s
    }

    private fun hubDlLabel(s: String?): String? = when (s) {
        "done" -> getString(R.string.dl_done)
        "inbox" -> getString(R.string.dl_inbox)
        "processing" -> getString(R.string.dl_processing)
        "stale" -> getString(R.string.dl_stale)
        "unqueued", "", null -> null
        else -> if (s.startsWith("failed")) getString(R.string.dl_failed, s) else s   // failed-download 등
    }

    private fun addParagraph(box: LinearLayout, label: String, text: String) {
        box.addView(TextView(this).apply {
            this.text = label
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 13f
            setPadding(0, dp(14), 0, dp(4))
        })
        box.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFFD0D0D0.toInt())
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
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
            width = dp(86)   // 이모지 표지 추가분 여유
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
