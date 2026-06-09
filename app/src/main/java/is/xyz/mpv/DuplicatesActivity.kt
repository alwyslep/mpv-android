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
//   타일=📁폴더·크기·해상도(MMR)·✅추천. 탭 재생/롱프레스 장면비교·삭제. 상단 '작은쪽 일괄정리'.
//   ⭐KEEP 추천 우선순위 = 임베드(커버 박힘) → 해상도 → 크기.
//     (임베드/remux 는 크기가 줄 수 있어 크기만으론 정본을 버리게 됨 — 사용자 지적 B-68.)
class DuplicatesActivity : AppCompatActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private var dups: List<Vid> = emptyList()
    private var groups: List<List<Vid>> = emptyList()
    private var driveTagFolders: Set<String> = emptySet()   // 동일폴더명이 여러 드라이브 충돌 → 태그
    private val keepUris = java.util.Collections.synchronizedSet(HashSet<String>())  // 안정 인스턴스(어댑터가 라이브 참조)
    private val mmrRes = ConcurrentHashMap<String, Int>()      // uri → 짧은변 px(MMR 보완)
    private val hasCover = ConcurrentHashMap<String, Boolean>() // uri → 임베드 커버 존재(MMR embeddedPicture)
    private val coverSet = java.util.Collections.synchronizedSet(HashSet<String>())  // 커버 있는 uri(어댑터 라이브 참조)
    private var pendingUri: String? = null
    private var adapter: VideoAdapter? = null
    private var gen = 0                                          // enrich 세대(stale 중단)

    private val playLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        pendingUri?.let { Playback.onResult(this, it, res.data) }
        load()   // 재생화면서 삭제 등 변경 반영
    }
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
            // 동일 폴더명이 여러 드라이브에 존재하면(충돌) 그 폴더명만 💾드라이브 태그 — 라이브러리 전체 기준.
            val volsByFolder = HashMap<String, MutableSet<String>>()
            for (v in all) if (v.folderName.isNotEmpty()) volsByFolder.getOrPut(v.folderName) { HashSet() }.add(v.volume)
            val tagFolders = volsByFolder.filterValues { it.size > 1 }.keys.toSet()
            val byCode = HashMap<String, MutableList<Vid>>()
            for (v in all) {
                val code = JavCode.dupKey(v.name) ?: continue   // variant 관용(무하이픈 폴백 포함)
                byCode.getOrPut(code) { ArrayList() }.add(v)
            }
            // 그룹 내 정렬: 해상도↓ → 크기↓ (표시 순서; KEEP 은 임베드 우선 별도 산출)
            val dupGroups = byCode.toSortedMap().filterValues { it.size > 1 }
                .map { it.value.sortedWith(compareByDescending<Vid> { minOf(it.width, it.height) }.thenByDescending { it.size }) }
            val list = dupGroups.flatten()
            JavDiag.log("dup", "raw=${raw.size} dedup=${all.size}  [빈경로=${raw.count { it.path.isEmpty() }} 해상도0=${raw.count { it.width == 0 }} 크기0=${raw.count { it.size == 0L }}]  중복그룹=${dupGroups.size}")
            dupGroups.take(25).forEach { vs ->
                JavDiag.log("dup", "  ${JavCode.dupKey(vs[0].name)} (${vs.size}): " + vs.joinToString(" | ") { "${it.name}·${MediaLibrary.fmtSize(it.size)}·${it.width}x${it.height}·📁${it.folderName}" })
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                dups = list
                groups = dupGroups
                driveTagFolders = tagFolders
                keepUris.clear(); keepUris.addAll(computeKeeps())   // 1차: 임베드 정보 전이라 사실상 크기 기준
                toolbar.title = "품번 중복  ${dupGroups.size}건 · ${list.size}개"
                rebuild()
                findViewById<android.view.View>(R.id.loading_bar)?.visibility = android.view.View.GONE
                if (list.isEmpty()) Toast.makeText(this, "품번 중복 없음 (${all.size}개 검사)", Toast.LENGTH_LONG).show()
                else enrich()   // 해상도 + 임베드여부 MMR 조사 → KEEP 재산출
            }
        }.start()
    }

    private fun rebuild() {
        val grid = LibPrefs.grid(this)
        recycler.layoutManager = if (grid) GridLayoutManager(this, LibPrefs.spanCount(this)) else LinearLayoutManager(this)
        adapter = VideoAdapter(dups.toMutableList(), grid, showFolder = true, keepUris = keepUris, resByUri = mmrRes, coverUris = coverSet, driveTagFolders = driveTagFolders) { v -> play(v) }
        recycler.adapter = adapter
    }

    // KEEP 추천 산출 — 그룹별: 임베드(커버) → 해상도 → 크기 순 최상.
    private fun computeKeeps(): Set<String> = groups.mapNotNull { grp ->
        grp.maxWithOrNull(
            compareBy<Vid>(
                { if (hasCover[it.uri.toString()] == true) 1 else 0 },              // 임베드 우선
                { mmrRes[it.uri.toString()] ?: minOf(it.width, it.height) },        // 해상도
                { it.size }                                                         // 크기
            )
        )?.uri?.toString()
    }.toSet()

    // 중복 타일 보강 — 각 dup 파일 MMR 1회 open 으로 (a)해상도(0x0 보완) (b)임베드 커버 여부 조사.
    //   → 끝나면 KEEP 을 임베드 우선으로 재산출(크기만으론 임베드 정본을 버리는 문제 해소).
    private fun enrich() {
        val myGen = ++gen
        val snapshot = dups
        if (snapshot.isEmpty()) return
        Thread {
            for ((idx, v) in snapshot.withIndex()) {
                if (myGen != gen) return@Thread
                val key = v.uri.toString()
                val needRes = (v.width <= 0 || v.height <= 0) && !mmrRes.containsKey(key)
                val needCover = !hasCover.containsKey(key)
                if (!needRes && !needCover) continue
                try {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (v.path.isNotEmpty()) mmr.setDataSource(v.path) else mmr.setDataSource(this, v.uri)
                        if (needRes) {
                            val rw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val rh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            if (rw > 0 && rh > 0) mmrRes[key] = minOf(rw, rh)
                        }
                        if (needCover) { val c = mmr.embeddedPicture != null; hasCover[key] = c; if (c) coverSet.add(key) }
                    } finally { mmr.release() }
                } catch (e: Throwable) { JavDiag.ex("dup.enrich", e); if (needCover) hasCover[key] = false }
                runOnUiThread { if (myGen == gen) adapter?.notifyItemChanged(idx) }
            }
            // 임베드/해상도 확보 후 KEEP 재산출 → 마커 갱신
            val keeps = computeKeeps()
            runOnUiThread {
                if (myGen != gen) return@runOnUiThread
                keepUris.clear(); keepUris.addAll(keeps)
                adapter?.notifyDataSetChanged()
                JavDiag.log("dup", "enrich 완료: 커버=${hasCover.count { it.value }}/${hasCover.size}  KEEP=${keeps.size}")
            }
        }.start()
    }

    // #2: 각 그룹 추천(KEEP) 외 나머지를 일괄 휴지통.
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
