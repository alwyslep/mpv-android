package `is`.xyz.mpv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

// B-68(52): 품번 중복 검수 화면 — 정적 리스트로는 검수 불가 → 타일로 펼친다.
//   전 라이브러리(MediaStore) 영상 중 JavCode 품번이 같은 2개↑ 를 모아 품번순 정렬(같은 품번 인접).
//   기존 VideoAdapter 재사용 → 커버·해상도·크기 비교 + 탭 재생(내용 확인) + 롱프레스(상세/이름변경/삭제)로 정리.
class DuplicatesActivity : AppCompatActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private var dups: List<Vid> = emptyList()
    private var pendingUri: String? = null

    private val playLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        pendingUri?.let { Playback.onResult(this, it, res.data) }
        load()   // 재생화면서 삭제 등 변경 반영
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)
        AuroraDrawable.apply(this)
        Utils.applyRtl(this)
        toolbar = findViewById(R.id.toolbar)
        toolbar.title = "품번 중복 검수"
        toolbar.setNavigationOnClickListener { finish() }
        recycler = findViewById(R.id.recycler)
        load()
    }

    private fun norm(c: String) = c.uppercase().replace("-", "").replace("_", "").replace(" ", "")

    private fun load() {
        findViewById<android.view.View>(R.id.loading_bar)?.visibility = android.view.View.VISIBLE
        Thread {
            val raw = MediaLibrary.queryVideos(this)
            // 같은 '파일'(MediaStore 중복행)만 합침: 경로 있으면 경로, 없으면 크기(바이트)+해상도+이름 으로 식별.
            val all = raw.distinctBy { v -> if (v.path.isNotEmpty()) v.path else "${v.size}|${v.width}x${v.height}|${v.name}" }
            val byCode = HashMap<String, MutableList<Vid>>()
            for (v in all) {
                val code = JavCode.extract(v.name)?.let { norm(it) } ?: continue
                byCode.getOrPut(code) { ArrayList() }.add(v)
            }
            // 그룹 내 정렬: 해상도↓ → 크기↓ (제일 좋은 버전 먼저)
            val list = byCode.toSortedMap().filterValues { it.size > 1 }
                .flatMap { it.value.sortedWith(compareByDescending<Vid> { minOf(it.width, it.height) }.thenByDescending { it.size }) }
            val groups = byCode.count { it.value.size > 1 }
            JavDiag.log("dup", "raw=${raw.size} dedup(같은파일합침)=${all.size}  [빈경로=${raw.count { it.path.isEmpty() }} 해상도0=${raw.count { it.width == 0 }} 크기0=${raw.count { it.size == 0L }}]  중복그룹=$groups")
            byCode.filterValues { it.size > 1 }.entries.take(25).forEach { (c, vs) ->
                JavDiag.log("dup", "  $c (${vs.size}): " + vs.joinToString(" | ") { "${it.name}·${it.size}B·${it.width}x${it.height}·${if (it.path.isEmpty()) "NOPATH" else java.io.File(it.path).parent?.substringAfterLast('/') ?: "?"}" })
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                dups = list
                toolbar.title = "품번 중복  ${groups}건 · ${list.size}개"
                rebuild()
                findViewById<android.view.View>(R.id.loading_bar)?.visibility = android.view.View.GONE
                if (list.isEmpty()) Toast.makeText(this, "품번 중복 없음 (${all.size}개 검사)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun rebuild() {
        val grid = LibPrefs.grid(this)
        recycler.layoutManager = if (grid) GridLayoutManager(this, LibPrefs.spanCount(this)) else LinearLayoutManager(this)
        recycler.adapter = VideoAdapter(dups.toMutableList(), grid) { v -> play(v) }
    }

    private fun play(v: Vid) {
        pendingUri = v.uri.toString()
        playLauncher.launch(Playback.intentFor(this, v.uri.toString(), v.name))
    }
}
