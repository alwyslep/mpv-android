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
    // 우리 임베드 판정(원본커버만 있는 건 제외) = JEmbed 메타(배우/스튜디오/시리즈/장르) 존재. covr 단독 아님.
    private val embedMap = ConcurrentHashMap<String, Boolean>() // uri → 우리 임베드 여부
    private val embedSet = java.util.Collections.synchronizedSet(HashSet<String>())  // 임베드된 uri(어댑터 라이브 참조)
    private val durMap = ConcurrentHashMap<String, Long>()      // uri → 재생길이ms(MMR; 잘린/부분 파일 식별)
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
            // 같은 '파일'(같은 드라이브 내 중복행)만 합침: 경로 있으면 경로, 없으면 드라이브+크기+해상도+이름.
            //  ※ volume 포함 필수 — 빼면 두 드라이브의 동일 복사본(같은 바이트)을 한 파일로 합쳐 교차드라이브 중복이 사라짐.
            val all = raw.distinctBy { v -> if (v.path.isNotEmpty()) v.path else "${v.volume}|${v.size}|${v.width}x${v.height}|${v.name}" }
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
            // 드라이브: 볼륨 분포 + 충돌(같은 폴더명이 여러 드라이브) 폴더
            JavDiag.log("dup", "볼륨분포: " + all.groupingBy { it.volume.ifEmpty { "(빈)" } }.eachCount().entries.joinToString { "${it.key}=${it.value}" })
            JavDiag.log("dup", "태그대상(충돌폴더 ${tagFolders.size}): " + volsByFolder.filterValues { it.size > 1 }.entries.joinToString(" ; ") { "${it.key}→{${it.value.joinToString(",")}}" })
            dupGroups.take(25).forEach { vs ->
                JavDiag.log("dup", "  ${JavCode.dupKey(vs[0].name)} (${vs.size}): " + vs.joinToString(" | ") { "${it.name}·${MediaLibrary.fmtSize(it.size)}·📁${it.folderName}·💾${it.volume.ifEmpty { "(빈)" }}" })
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
        adapter = VideoAdapter(dups.toMutableList(), grid, showFolder = true, keepUris = keepUris, resByUri = mmrRes,
            embedUris = embedSet, driveTagFolders = driveTagFolders,
            onItemRemoved = { uri -> reconcileAfterRemove(uri) }) { v -> play(v) }
        recycler.adapter = adapter
    }

    // 타일 1개 휴지통 후 즉시 반영(재로드/재스캔 없이): 그룹이 1개만 남으면 생존자도 제거(중복 해소),
    //   카운트·✅추천 갱신. 3개↑ 그룹은 유지하되 KEEP 재산출.
    private fun reconcileAfterRemove(removedUri: String) {
        val newGroups = ArrayList<List<Vid>>()
        val alsoRemove = ArrayList<String>()
        var resolvedCode: String? = null
        for (g in groups) {
            if (g.none { it.uri.toString() == removedUri }) { newGroups.add(g); continue }
            val remain = g.filterNot { it.uri.toString() == removedUri }
            if (remain.size >= 2) newGroups.add(remain)                         // 아직 중복(3개↑→2개↑)
            else { remain.forEach { alsoRemove.add(it.uri.toString()) }         // 해소 — 생존자도 제거
                   resolvedCode = remain.firstOrNull()?.let { JavCode.dupKey(it.name) } }
        }
        groups = newGroups
        dups = newGroups.flatten()
        alsoRemove.forEach { adapter?.removeItem(it) }
        keepUris.clear(); keepUris.addAll(computeKeeps())
        toolbar.title = "품번 중복  ${groups.size}건 · ${dups.size}개"
        adapter?.notifyDataSetChanged()
        resolvedCode?.let { Toast.makeText(this, "「$it」 중복 정리 — 1개 남김", Toast.LENGTH_SHORT).show() }
        if (dups.isEmpty()) Toast.makeText(this, "중복 모두 정리됨", Toast.LENGTH_SHORT).show()
    }

    // KEEP 추천 산출 — 그룹별: 우리 임베드(메타) → 재생길이(분버킷,완본 우선) → 해상도 → 크기.
    //   길이는 분 단위 버킷이라 인코딩 오차(초)는 무시되고, 잘린/부분(반토막) 파일만 밀려난다.
    private fun computeKeeps(): Set<String> = groups.mapNotNull { grp ->
        grp.maxWithOrNull(
            compareBy<Vid>(
                { if (embedMap[it.uri.toString()] == true) 1 else 0 },              // 우리 임베드 우선
                { (durMap[it.uri.toString()] ?: it.durationMs) / 60000 },           // 재생길이(분) — 완본 우선
                { mmrRes[it.uri.toString()] ?: minOf(it.width, it.height) },        // 해상도
                { it.size }                                                         // 크기
            )
        )?.uri?.toString()
    }.toSet()

    // 중복 타일 보강 — 각 dup 파일 MMR 1회 open 으로 (a)해상도(0x0 보완) (b)우리 임베드 여부 조사.
    //   임베드 판정 = JEmbed 메타(배우 ©ART / 스튜디오 ©aART / 시리즈 ©alb / 장르 ©gen) 존재.
    //   ※ covr(커버) 단독은 원본 다운로드에도 흔해 제외 — 우리 메타가 박혀야 '임베드본'.
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
                val needEmbed = !embedMap.containsKey(key)
                val needDur = v.durationMs <= 0 && !durMap.containsKey(key)
                if (!needRes && !needEmbed && !needDur) continue
                try {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (v.path.isNotEmpty()) mmr.setDataSource(v.path) else mmr.setDataSource(this, v.uri)
                        if (needRes) {
                            val rw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val rh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            if (rw > 0 && rh > 0) mmrRes[key] = minOf(rw, rh)
                        }
                        if (needDur) durMap[key] = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        if (needEmbed) {
                            fun m(k: Int) = mmr.extractMetadata(k)?.trim().orEmpty()
                            val embedded = m(MediaMetadataRetriever.METADATA_KEY_ARTIST).isNotEmpty() ||      // 배우
                                m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).isNotEmpty() ||            // 스튜디오
                                m(MediaMetadataRetriever.METADATA_KEY_ALBUM).isNotEmpty() ||                  // 시리즈
                                m(MediaMetadataRetriever.METADATA_KEY_GENRE).isNotEmpty()                     // 장르
                            embedMap[key] = embedded; if (embedded) embedSet.add(key)
                        }
                    } finally { mmr.release() }
                } catch (e: Throwable) { JavDiag.ex("dup.enrich", e); if (needEmbed) embedMap[key] = false }
                runOnUiThread { if (myGen == gen) adapter?.notifyItemChanged(idx) }
            }
            // 임베드/해상도 확보 후 KEEP 재산출 → 마커 갱신
            val keeps = computeKeeps()
            runOnUiThread {
                if (myGen != gen) return@runOnUiThread
                keepUris.clear(); keepUris.addAll(keeps)
                adapter?.notifyDataSetChanged()
                JavDiag.log("dup", "enrich 완료: 임베드=${embedMap.count { it.value }}/${embedMap.size}  KEEP=${keeps.size}")
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
