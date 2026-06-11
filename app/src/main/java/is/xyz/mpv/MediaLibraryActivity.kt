package `is`.xyz.mpv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

// P2: NextPlayer식 홈 — MediaStore 비디오 폴더 목록 + SpeedDial FAB. 런처 진입점.
class MediaLibraryActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var toolbar: MaterialToolbar   // 54: 아이콘 active 틴트용 보관
    private lateinit var empty: TextView
    private lateinit var fabMenu: View
    private lateinit var selCtl: SelectionController   // jembed/이동 선택모드 (공통 컨트롤러)
    private var seeded = false   // 번들 스크립트/conf 시드 1회 플래그
    // 55: 툴바 메뉴 커스텀 툴팁 텍스트(가시성 토글되는 5/7/8 포함 — load()서 재부착)
    private val menuTips: Map<Int, CharSequence> = mapOf(
        2 to "품번·제목으로 검색", 4 to "배우·스튜디오·시리즈·장르로 분류 보기",
        9 to "정렬 기준·오름/내림차순 변경", 3 to "표시 항목·커버 크기 등 빠른 설정",
        1 to "앱 설정 화면", 5 to "여러 영상을 골라 일괄 임베드/이동/썸네일 지정 (videos 모드)",
        6 to "해상도·상태·확장자·메타 조건으로 목록 필터", 7 to "선택한 영상을 다른 폴더로 이동",
        8 to "선택 영상의 썸네일 위치를 일괄 지정",
        10 to "현재 목록에서 커버 없는 영상에 hub 커버를 일괄 임베드(있으면 건너뜀)"
    )

    // 로컬/USB 폴더 1개 선택 → 내 SAF 타일 브라우저로 진입(OS 선택기 대신).
    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: Exception) {
                }
                SafTrees.add(this, it.toString())  // 통합 검색 인덱싱 대상에 등록
                val title = (it.lastPathSegment ?: getString(R.string.qs_folder)).substringAfterLast(":").substringAfterLast("/")
                startActivity(
                    Intent(this, SafBrowserActivity::class.java)
                        .putExtra("tree", it.toString())
                        .putExtra("title", title)
                )
            }
        }

    private val reqPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    // 재생 결과(위치/길이) 기록 — 이어보기·진행률
    private var pendingUri: String? = null
    private var scrollState: android.os.Parcelable? = null   // 31: 스크롤 위치 — onPause 저장 + Bundle 영속화(Activity 파괴 대비)
    private var homeVids: List<Pair<String, String>> = emptyList()  // 자동 다음재생용 (uri, name)
    private var playIndex = -1
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            // B-68: 삭제/이동으로 제거됐으면 다음 영상으로 advance.
            val rmNext = if (Playback.wasRemoved(res.data) && u != null) {
                val i = homeVids.indexOfFirst { it.first == u }; if (i >= 0) homeVids.getOrNull(i + 1) else null
            } else null
            if (rmNext != null) play(rmNext.first, rmNext.second)
            else if (u != null && Playback.shouldAdvance(this, u, homeVids.find { it.first == u }?.second) && playIndex + 1 in homeVids.indices) {
                val (nu, nn) = homeVids[playIndex + 1]
                play(nu, nn)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_library)
        AuroraDrawable.apply(this)
        Utils.applyRtl(this)   // 58: RTL 레이아웃 토글
        @Suppress("DEPRECATION")
        savedInstanceState?.getParcelable<android.os.Parcelable>("ml_scroll")?.let { scrollState = it }  // 31: 파괴→재생성 복원

        toolbar = findViewById(R.id.toolbar)
        setupToolbar()   // B-68(52): 공통 LibToolbar (viewMode 따라 select/move/thumb 활성)

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        empty = findViewById(R.id.empty)
        selCtl = SelectionController(this, findViewById(R.id.sel_bar), findViewById(R.id.sel_count)) { load() }
        findViewById<View>(R.id.sel_all).setOnClickListener { selCtl.selectAll() }
        findViewById<View>(R.id.sel_cancel).setOnClickListener { selCtl.exit() }
        findViewById<View>(R.id.sel_embed).setOnClickListener { selCtl.embedBatch() }
        findViewById<View>(R.id.sel_move).setOnClickListener { selCtl.moveBatch() }
        findViewById<View>(R.id.sel_thumb).setOnClickListener { selCtl.thumbBatch() }

        setupFab()
        ensurePermissionThenLoad()
    }

    override fun onResume() {
        super.onResume()
        Utils.applyRtl(this)   // 58: 설정에서 토글 후 복귀 시 즉시 반영
        setupToolbar()         // 55b: 아이콘 순서 변경(ToolbarOrderActivity) 후 복귀 시 반영
        // 재생 후 복귀 시 최근목록/신규영상 반영
        if (hasMediaAccess()) load()
    }

    override fun onPause() {
        super.onPause()   // 31: 재생/이탈 전 스크롤 위치 저장
        recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)   // 31: Activity 파괴 대비 영속화
        scrollState?.let { outState.putParcelable("ml_scroll", it) }
    }

    private fun setupFab() {
        fabMenu = findViewById(R.id.fab_menu)
        findViewById<FloatingActionButton>(R.id.fab_main).setOnClickListener {
            fabMenu.visibility = if (fabMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fab_url).setOnClickListener {
            fabMenu.visibility = View.GONE; showUrlDialog()
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fab_local).setOnClickListener {
            fabMenu.visibility = View.GONE
            try {
                openTree.launch(null)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_no_folder_picker), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fab_recent).setOnClickListener {
            fabMenu.visibility = View.GONE; showRecents()
        }
        // B-64(47): FAB 툴팁
        Utils.tip(findViewById(R.id.fab_main), "추가 메뉴 열기/닫기")
        Utils.tip(findViewById(R.id.fab_url), "URL 직접 입력해 열기")
        Utils.tip(findViewById(R.id.fab_local), "폴더 열기 — USB/SD 등 외부저장소 SAF 등록")
        Utils.tip(findViewById(R.id.fab_recent), "최근 연 항목")
    }

    private fun hasMediaAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
            return true
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionThenLoad() {
        if (hasMediaAccess()) {
            load()
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        reqPerm.launch(perm)
    }

    private fun spanCount(): Int = LibPrefs.spanCount(this)

    private fun load() {
        val mode = LibPrefs.viewMode(this)
        val grid = LibPrefs.grid(this)
        // 31: 어댑터에 아이템이 있을 때만 현재 위치로 갱신(재생성 직후 빈 LM 이 0 으로 덮어쓰는 것 방지).
        //     필드 기반 → onPause 저장 + Bundle 복원 모두 활용. 메뉴 액션(정렬/필터) 시엔 현재 위치 유지.
        if ((recycler.adapter?.itemCount ?: 0) > 0)
            recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
        val savedScroll = scrollState
        if (mode != "videos") { selCtl.exit(); selCtl.unbind() }
        setupToolbar()   // viewMode 따라 select/move/thumb 활성 + 아이콘 틴트 갱신(LibToolbar 재빌드)
        DurationHub.fetchAsync(this)   // v6: hub 길이맵 1회 채움(길이 불일치 마커용)
        ResolutionHub.fetchAsync(this) // 4: hub 해상도맵 1회 채움(해상도 불일치 마커용)
        MetaHub.fetchAsync(this)       // 필터: hub 메타맵 1회 채움(메타 필터용)
        Thread {
            if (!seeded) { Utils.seedConfig(this); seeded = true }   // 번들 기본 스크립트/conf 복원(없을 때만)
            val allVids = MediaLibrary.queryVideos(this)
            if (mode == "videos") {
                val vids = LibPrefs.sortVids(this, "home_videos", allVids).filter { FilterEngine.passes(this, it) }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    homeVids = vids.map { it.uri.toString() to it.name }
                    empty.visibility = if (vids.isEmpty()) View.VISIBLE else View.GONE
                    recycler.layoutManager =
                        if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
                    val va = VideoAdapter(vids.toMutableList(), grid) { v -> play(v.uri.toString(), v.name) }
                    selCtl.bind(va)
                    recycler.adapter = va
                    recycler.layoutManager?.onRestoreInstanceState(savedScroll)
                }
            } else if (mode == "tree") {
                val root = MediaLibrary.treeRoot(allVids)
                val children = MediaLibrary.treeChildren(this, allVids, root)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    empty.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
                    if (grid) {
                        val glm = GridLayoutManager(this, spanCount())
                        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                            override fun getSpanSize(position: Int): Int =
                                if (position < children.size && children[position].dirPath != null) spanCount() else 1
                        }
                        recycler.layoutManager = glm
                    } else {
                        recycler.layoutManager = LinearLayoutManager(this)
                    }
                    homeVids = children.mapNotNull { it.vid }.map { it.uri.toString() to it.name }
                    recycler.adapter = TreeAdapter(children.toMutableList(), grid,
                        onDir = { e ->
                            startActivity(
                                Intent(this, TreeActivity::class.java)
                                    .putExtra("dir", e.dirPath)
                                    .putExtra("title", e.name)
                            )
                        },
                        onVideo = { e -> e.vid?.let { play(it.uri.toString(), it.name) } }
                    )
                    recycler.layoutManager?.onRestoreInstanceState(savedScroll)
                }
            } else {
                // folder
                val baseFolds = LibPrefs.sortFolds(this, "home_folders", MediaLibrary.folders(allVids))
                // 74: 내장 파일 시스템 휴지통(IS_TRASHED)을 홈 최상단 가상 '🗑 휴지통' 폴더로 노출(있을 때만).
                val sysTrash = MediaLibrary.queryTrashed(this)
                val folds = if (sysTrash.isNotEmpty())
                    listOf(Fold("🗑 휴지통", MediaLibrary.SYS_TRASH_PATH, sysTrash.size, sysTrash.first())) + baseFolds
                else baseFolds
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    empty.visibility = if (folds.isEmpty()) View.VISIBLE else View.GONE
                    recycler.layoutManager =
                        if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
                    recycler.adapter = FolderAdapter(folds, grid,
                        onLongClick = { f ->   // 53: 휴지통 폴더 롱프레스 → 일괄 영구삭제
                            if (f.path.contains(".mpv-trash") || f.name.contains(".mpv-trash")) {
                                val items = MediaLibrary.videosIn(allVids, f.path).map { it.uri to it.name }
                                VideoTrash.bulkPermanentDeleteConfirm(this, items) { load() }
                            }
                        }) { f ->
                        startActivity(
                            Intent(this, FolderVideosActivity::class.java)
                                .putExtra("path", f.path)
                                .putExtra("name", f.name)
                        )
                    }
                    recycler.layoutManager?.onRestoreInstanceState(savedScroll)
                }
            }
        }.start()
    }

    // 54: 정렬(9)·빠른설정(3)·필터(6) — 기본값과 다른 설정이 걸렸으면 amber 로 틴트, 아니면 기본색.
    // B-68(52): 공통 12아이콘 툴바. 홈 활성 = search/classify/sort/tune/settings/filter/cover (+videos 모드면 select/move/thumb).
    private fun setupToolbar() {
        val videos = LibPrefs.viewMode(this) == "videos"
        val en = mutableSetOf("search", "classify", "sort", "tune", "settings", "filter", "cover", "dup", "refresh")
        if (videos) en.addAll(listOf("select", "move", "thumb", "embedauto"))
        LibToolbar.build(toolbar, en, null) { key ->
            when (key) {
                "refresh" -> load()
                "embedauto" -> EmbedAuto.confirm(this, MediaLibrary.queryVideos(this).map { it.uri to it.name }) { load() }
                "search" -> startActivity(Intent(this, SearchActivity::class.java))
                "classify" -> startActivity(Intent(this, BrowseActivity::class.java))
                "dup" -> startActivity(Intent(this, DuplicatesActivity::class.java))   // 타일 검수 화면
                "sort" -> {
                    val m = LibPrefs.viewMode(this)
                    val sc = when (m) { "videos" -> "home_videos"; "tree" -> "home_tree"; else -> "home_folders" }
                    SortDialog.show(this, sc, m == "folder") { load() }
                }
                "tune" -> QuickSettings.show(this) { load() }
                "settings" -> startActivity(Intent(this, `is`.xyz.mpv.preferences.PreferenceActivity::class.java))
                "select" -> selCtl.enter(showEmbed = true, showMove = false)
                "filter" -> FilterSheet.show(this) { load() }
                "move" -> selCtl.enter(showEmbed = false, showMove = true)
                "thumb" -> selCtl.enter(showEmbed = false, showMove = false, showThumb = true)
                "cover" -> coverFixAll()
            }
        }
        applyIconTints()
    }

    private fun applyIconTints() {
        val active = ContextCompat.getColor(this, R.color.icon_active)
        val normal = com.google.android.material.color.MaterialColors.getColor(
            toolbar, com.google.android.material.R.attr.colorOnSurface)
        fun tint(key: String, on: Boolean) {
            toolbar.menu.findItem(LibToolbar.menuId(key))?.icon?.mutate()?.setTint(if (on) active else normal)
        }
        tint("sort", LibPrefs.sortActive(this))    // key 기준(재배치-안전)
        tint("tune", LibPrefs.quickActive(this))
        tint("filter", LibPrefs.filterActive(this))
    }

    // B-52 A: 현재 목록(videos/tree) 영상 중 커버 없는 것만 hub 커버 재임베드(JEmbed skipIfHasCover).
    private fun coverFixAll() {
        val items = homeVids.map { (u, n) -> Uri.parse(u) to (JavCode.extract(n) ?: "") }
        if (items.isEmpty()) { Toast.makeText(this, "영상 목록에서 사용하세요(폴더 모드 아님)", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("커버 보강")
            .setMessage("현재 목록 ${"%,d".format(items.size)}개 중 커버 없는 영상에 hub 커버를 임베드합니다(이미 있으면 건너뜀).")
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .setPositiveButton("진행") { _, _ -> selCtl.coverFixBatch(items) }
            .show()
    }

    private fun play(uri: String, title: String) {
        playIndex = homeVids.indexOfFirst { it.first == uri }
        pendingUri = uri
        playLauncher.launch(Playback.intentFor(this, uri, title))
    }

    private fun showUrlDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.hint_stream_url)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lbl_open_network_stream))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_play)) { _, _ ->
                val u = input.text.toString().trim()
                if (u.isNotEmpty()) play(u, u)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun showRecents() {
        val recents = Recents.list(this)
        if (recents.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_recent), Toast.LENGTH_SHORT).show()
            return
        }
        val titles = recents.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lbl_recent))
            .setItems(titles) { _, which ->
                val (uri, title) = recents[which]
                play(uri, title)
            }
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selCtl.isActive) selCtl.exit() else super.onBackPressed()
    }
}
