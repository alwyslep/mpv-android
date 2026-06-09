package `is`.xyz.mpv

import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.ConcurrentHashMap

// B-68(52): 품번 중복 검수 화면 — 정적 리스트로는 검수 불가 → 타일로 펼친다.
//   전 라이브러리(MediaStore) 영상 중 JavCode 품번이 같은 2개↑ 를 모아 품번순 정렬(같은 품번 인접).
//   타일=📁폴더·크기·해상도(MMR)·✅추천(최대용량). 탭 재생/롱프레스 장면비교·삭제. 상단 '작은쪽 일괄정리'.
class DuplicatesActivity : AppCompatActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private var dups: List<Vid> = emptyList()
    private var keepUris: Set<String> = emptySet()
    private var pendingUri: String? = null
    private var adapter: VideoAdapter? = null
    private val mmrRes = ConcurrentHashMap<String, Int>()   // uri → 짧은변 px(MMR 보완)
    private var gen = 0                                       // MMR 채움 세대(stale 중단)

    private val playLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        pendingUri?.let { Playback.onResult(this, it, res.data) }
        load()   // 재생화면서 삭제 등 변경 반영
    }
    // 일괄 휴지통(createTrashRequest) OS 확인창 결과 → 재로드
    private val trashLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { load() }

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "작은쪽 일괄정리").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { bulkCleanup(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        findViewById<android.view.View>(R.id.loading_bar)?.visibility = android.view.View.VISIBLE
        Thread {
            val raw = MediaLibrary.queryVideos(this)
                .filter { !MediaLibrary.isTrashOrTemp(it) }   // 휴지통/remux임시/크기0 제외
            // 같은 '파일'(MediaStore 중복행)만 합침: 경로 있으면 경로, 없으면 크기(바이트)+해상도+이름 으로 식별.
            val all = raw.distinctBy { v -> if (v.path.isNotEmpty()) v.path else "${v.size}|${v.width}x${v.height}|${v.name}" }
            val byCode = HashMap<String, MutableList<Vid>>()
            for (v in all) {
                val code = JavCode.dupKey(v.name) ?: continue   // variant 관용(무하이픈 폴백 포함)
                byCode.getOrPut(code) { ArrayList() }.add(v)
            }
            // 그룹 내 정렬: 해상도↓ → 크기↓ (제일 좋은 버전 먼저)
            val dupGroups = byCode.toSortedMap().filterValues { it.size > 1 }
            val list = dupGroups.flatMap { it.value.sortedWith(compareByDescending<Vid> { minOf(it.width, it.height) }.thenByDescending { it.size }) }
            // KEEP 추천 = 그룹별 최대 용량(가장 신뢰 가능한 신호 — 해상도는 93%가 MediaStore 0)
            val keeps = dupGroups.values.mapNotNull { grp -> grp.maxByOrNull { it.size }?.uri?.toString() }.toSet()
            val groups = dupGroups.size
            JavDiag.log("dup", "raw=${raw.size} dedup(같은파일합침)=${all.size}  [빈경로=${raw.count { it.path.isEmpty() }} 해상도0=${raw.count { it.width == 0 }} 크기0=${raw.count { it.size == 0L }}]  중복그룹=$groups")
            dupGroups.entries.take(25).forEach { (c, vs) ->
                JavDiag.log("dup", "  $c (${vs.size}): " + vs.joinToString(" | ") { "${it.name}·${MediaLibrary.fmtSize(it.size)}·${it.width}x${it.height}·📁${it.folderName}" })
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                dups = list
                keepUris = keeps
                toolbar.title = "품번 중복  ${groups}건 · ${list.size}개"
                rebuild()
                findViewById<android.view.View>(R.id.loading_bar)?.visibility = android.view.View.GONE
                if (list.isEmpty()) Toast.makeText(this, "품번 중복 없음 (${all.size}개 검사)", Toast.LENGTH_LONG).show()
                else fillResolutions()   // #1: MediaStore 0x0 타일 해상도 MMR 백그라운드 채움
            }
        }.start()
    }

    private fun rebuild() {
        val grid = LibPrefs.grid(this)
        recycler.layoutManager = if (grid) GridLayoutManager(this, LibPrefs.spanCount(this)) else LinearLayoutManager(this)
        adapter = VideoAdapter(dups.toMutableList(), grid, showFolder = true, keepUris = keepUris, resByUri = mmrRes) { v -> play(v) }
        recycler.adapter = adapter
    }

    // 중복 타일 해상도 보완 — MediaStore 0x0(93%) 인 항목만 MMR 로 짧은변 추출 후 배지 갱신.
    private fun fillResolutions() {
        val myGen = ++gen
        val targets = dups.withIndex().filter { it.value.width <= 0 || it.value.height <= 0 }
        if (targets.isEmpty()) return
        Thread {
            for ((idx, v) in targets) {
                if (myGen != gen) return@Thread
                val key = v.uri.toString()
                if (mmrRes.containsKey(key)) continue
                val short = try {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (v.path.isNotEmpty()) mmr.setDataSource(v.path) else mmr.setDataSource(this, v.uri)
                        val rw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val rh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        if (rw > 0 && rh > 0) minOf(rw, rh) else 0
                    } finally { mmr.release() }
                } catch (e: Throwable) { JavDiag.ex("dup.mmrRes", e); 0 }
                if (short > 0) {
                    mmrRes[key] = short
                    runOnUiThread { if (myGen == gen) adapter?.notifyItemChanged(idx) }
                }
            }
        }.start()
    }

    // #2: 각 그룹 추천(최대용량)만 남기고 나머지(작은쪽)를 일괄 휴지통.
    private fun bulkCleanup() {
        val victims = dups.filter { it.uri.toString() !in keepUris }.map { it.uri to it.name }
        if (victims.isEmpty()) { Toast.makeText(this, "정리할 작은 중복 없음", Toast.LENGTH_SHORT).show(); return }
        VideoTrash.bulkTrashConfirm(this, victims, trashLauncher) { load() }
    }

    private fun play(v: Vid) {
        pendingUri = v.uri.toString()
        playLauncher.launch(Playback.intentFor(this, v.uri.toString(), v.name))
    }
}
