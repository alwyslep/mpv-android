package `is`.xyz.mpv

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

// P2: 폴더 진입 → 해당 폴더의 비디오. 목록/타일 토글, 타일은 임베드 커버+제목. 탭 시 곧장 재생.
class FolderVideosActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private var vids: List<Vid> = emptyList()
    private var grid = true
    private lateinit var toolbar: MaterialToolbar
    private var folderPath = ""
    private lateinit var selCtl: SelectionController

    private var pendingUri: String? = null
    private var playIndex = -1
    private var scrollState: android.os.Parcelable? = null   // 31: 스크롤 보존(onPause+Bundle)
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            // B-68: 삭제/이동으로 제거됐으면 리스트 갱신 전에 다음 영상 캡처(인덱스 당겨짐 방지) → advance.
            val rmNext = if (Playback.wasRemoved(res.data) && u != null) {
                val i = vids.indexOfFirst { it.uri.toString() == u }; if (i >= 0) vids.getOrNull(i + 1) else null
            } else null
            val adv = Playback.advanceDir(res.data)   // PgDn(+1)/PgUp(-1) 수동 넘김
            rebuild()
            // 다음에 재생할 대상을 *현재* playIndex 로 먼저 확정.
            val next: Vid? = when {
                // 삭제/이동된 경우: 제거된 항목 *다음*으로(9 삭제→10). 끝이면 없음.
                rmNext != null -> rmNext
                // PgUp/PgDn 수동 넘김: 현재 영상 기준 prev/next. 경계 밖이면 그대로 폴더에 머무름.
                adv != 0 && playIndex + adv in vids.indices -> vids[playIndex + adv]
                // 자동 다음 재생: 방금 작품을 끝까지 봤고(다 봄) 다음이 있으면.
                u != null && Playback.shouldAdvance(this, u, vids.find { it.uri.toString() == u }?.name) && playIndex + 1 in vids.indices ->
                    vids[playIndex + 1]
                else -> null
            }
            // ⚠️ 콜백 *안*에서 launcher.launch 를 재호출하면 연쇄(콜백→launch→콜백→launch) 2단째부터
            //   ActivityResult 레지스트리 상태 충돌로 실행이 누락돼 폴더로 죽는다(PgDn 연속 시 3번째 실패).
            //   → post 로 콜백을 빠져나온 뒤(다음 프레임, 완전 RESUMED) 실행해 매 launch 를 독립시킨다.
            if (next != null) recycler.post { play(next) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)
        AuroraDrawable.apply(this)
        Utils.applyRtl(this)   // 58: RTL 레이아웃 토글
        @Suppress("DEPRECATION")
        savedInstanceState?.getParcelable<android.os.Parcelable>("scroll")?.let { scrollState = it }  // 31

        folderPath = intent.getStringExtra("path") ?: ""
        val name = intent.getStringExtra("name") ?: getString(R.string.qs_folder)

        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        grid = prefs.getBoolean("video_grid", true)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }
        MetaHub.fetchAsync(this)
        setupToolbar()   // B-68(52): 공통 LibToolbar

        recycler = findViewById(R.id.recycler)
        ResolutionHub.fetchAsync(this)  // 48: hub 해상도맵(폴더 직진입 시도 채움 — fetchHubRes/마커용)
        selCtl = SelectionController(this, findViewById(R.id.sel_bar), findViewById<TextView>(R.id.sel_count)) { reload() }
        findViewById<View>(R.id.sel_all).setOnClickListener { selCtl.selectAll() }
        findViewById<View>(R.id.sel_thumb).setOnClickListener { selCtl.thumbBatch() }
        findViewById<View>(R.id.sel_cancel).setOnClickListener { selCtl.exit() }
        findViewById<View>(R.id.sel_embed).setOnClickListener { selCtl.embedBatch() }
        findViewById<View>(R.id.sel_move).setOnClickListener { selCtl.moveBatch() }
        findViewById<View>(R.id.sel_delete).setOnClickListener { selCtl.deleteBatch() }   // 53: 휴지통 일괄 영구삭제
        rebuild()
        reload()
    }

    private fun reload() {
        findViewById<View>(R.id.loading_bar)?.visibility = View.VISIBLE   // B-64(45): 필터/로드 진행 표시
        Thread {
            // 74: 시스템 휴지통(가상 폴더)이면 IS_TRASHED 영상 목록, 아니면 일반 폴더(+필터).
            val list = if (folderPath == MediaLibrary.SYS_TRASH_PATH)
                LibPrefs.sortVids(this, "folder", MediaLibrary.queryTrashed(this))
            else
                LibPrefs.sortVids(this, "folder", MediaLibrary.videosIn(MediaLibrary.queryVideos(this), folderPath))
                    .filter { FilterEngine.passes(this, it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                vids = list
                rebuild()
                updateTitle()
                findViewById<View>(R.id.loading_bar)?.visibility = View.GONE
            }
        }.start()
    }

    // B-63(34): 폴더명 옆에 총 영상 개수를 작게(회색) 표시. (선택 모드 개수는 SelectionController.update)
    private fun updateTitle() {
        val name = intent.getStringExtra("name") ?: getString(R.string.qs_folder)
        val suffix = "   ${"%,d".format(vids.size)}편"   // B-64(44): 천단위 콤마
        val sp = android.text.SpannableString(name + suffix)
        val s = name.length; val e = name.length + suffix.length
        sp.setSpan(android.text.style.RelativeSizeSpan(0.72f), s, e, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(android.text.style.ForegroundColorSpan(0xFFAAAAAA.toInt()), s, e, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<MaterialToolbar>(R.id.toolbar).title = sp
    }

    // B-68(52): 공통 12아이콘 툴바. 폴더 화면 활성 = toggle/sort/tune/select/filter/move/thumb/heal (나머지 회색).
    private val isTrash get() = folderPath.contains(".mpv-trash") || folderPath == MediaLibrary.SYS_TRASH_PATH

    private fun setupToolbar() {
        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        // 휴지통 폴더: 임베드/이동/복구 대신 영구삭제 위주(select=영구삭제 모드).
        val enabled = if (isTrash) setOf("toggle", "sort", "tune", "select", "filter", "dup", "refresh")
                      else setOf("toggle", "sort", "tune", "select", "embedauto", "filter", "move", "thumb", "heal", "dup", "refresh")
        LibToolbar.build(toolbar, enabled, grid) { key ->
            fun folderVids() = LibPrefs.sortVids(this, "folder", MediaLibrary.videosIn(MediaLibrary.queryVideos(this), folderPath)).map { it.uri to it.name }
            when (key) {
                "refresh" -> reload()
                "embedauto" -> EmbedAuto.confirm(this, folderVids()) { reload() }
                "toggle" -> { grid = !grid; prefs.edit().putBoolean("video_grid", grid).apply(); setupToolbar(); rebuild() }
                "sort" -> SortDialog.show(this, "folder", false) { reload() }
                "tune" -> QuickSettings.show(this) { grid = LibPrefs.grid(this); setupToolbar(); reload() }
                "select" -> if (isTrash) selCtl.enter(showEmbed = false, showMove = false, showDelete = true)
                            else selCtl.enter(showEmbed = true, showMove = false)
                "filter" -> FilterSheet.show(this) { reload() }
                "move" -> selCtl.enter(showEmbed = false, showMove = true)
                "thumb" -> selCtl.enter(showEmbed = false, showMove = false, showThumb = true)
                "heal" -> VideoHeal.healFolderConfirm(this, folderVids()) { reload() }
                "dup" -> startActivity(android.content.Intent(this, DuplicatesActivity::class.java))   // 타일 검수 화면
            }
        }
    }

    private fun spanCount(): Int = LibPrefs.spanCount(this)

    private fun rebuild() {
        // 31: LM 재생성 전(기존, 아이템 있을 때만) 위치 저장 → 필드. 재생성/복귀 모두 보존.
        if ((recycler.adapter?.itemCount ?: 0) > 0)
            recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
        val va = VideoAdapter(vids.toMutableList(), grid) { v -> play(v) }
        selCtl.bind(va)
        recycler.adapter = va
        recycler.layoutManager?.onRestoreInstanceState(scrollState)
    }

    override fun onResume() {
        super.onResume()
        setupToolbar()   // 55b: 아이콘 순서 변경 후 복귀 시 반영
        if (VideoTrash.consumePendingReload()) reload()   // 74: 삭제(휴지통/영구) 후 정합(취소 복원·확정 반영)
    }

    override fun onPause() {
        super.onPause()
        if (::recycler.isInitialized) recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        scrollState?.let { outState.putParcelable("scroll", it) }
    }

    private fun play(v: Vid) {
        playIndex = vids.indexOfFirst { it.uri == v.uri }
        pendingUri = v.uri.toString()
        // 폴더 경로 전달 → 플레이어가 PgDn/PgUp 로 폴더 내 다음/이전을 *내부 loadfile* 로 즉시 전환
        //   (singleTask 라 finish-후-재실행은 연속 시 결과 체인이 깨짐 → 내부 전환이 빠르고 안정).
        val i = Playback.intentFor(this, v.uri.toString(), v.name).putExtra("folder_path", folderPath)
        playLauncher.launch(i)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selCtl.isActive) selCtl.exit() else super.onBackPressed()
    }
}
