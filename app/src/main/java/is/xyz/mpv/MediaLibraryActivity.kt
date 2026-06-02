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
    private lateinit var empty: TextView
    private lateinit var fabMenu: View
    private var videoAdapter: VideoAdapter? = null   // jembed 선택모드
    private lateinit var selBar: View
    private lateinit var selCount: TextView

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
    private var homeVids: List<Pair<String, String>> = emptyList()  // 자동 다음재생용 (uri, name)
    private var playIndex = -1
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            if (u != null && Playback.shouldAdvance(this, u) && playIndex + 1 in homeVids.indices) {
                val (nu, nn) = homeVids[playIndex + 1]
                play(nu, nn)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_library)
        AuroraDrawable.apply(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.menu.add(0, 2, 0, getString(R.string.lbl_search)).apply {
            setIcon(R.drawable.ic_search_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 4, 1, getString(R.string.menu_classify)).apply {
            setIcon(R.drawable.ic_people_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 3, 2, getString(R.string.qs_title)).apply {
            setIcon(R.drawable.ic_tune_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 1, 2, getString(R.string.lbl_settings)).apply {
            setIcon(R.drawable.ic_settings_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 5, 3, "선택(임베드)").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> startActivity(Intent(this, SearchActivity::class.java))
                4 -> startActivity(Intent(this, BrowseActivity::class.java))
                3 -> QuickSettings.show(this) { load() }
                1 -> startActivity(Intent(this, `is`.xyz.mpv.preferences.PreferenceActivity::class.java))
                5 -> enterSelection()
            }
            true
        }

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        empty = findViewById(R.id.empty)
        selBar = findViewById(R.id.sel_bar)
        selCount = findViewById(R.id.sel_count)
        findViewById<View>(R.id.sel_cancel).setOnClickListener { exitSelection() }
        findViewById<View>(R.id.sel_embed).setOnClickListener { doEmbedBatch() }
        findViewById<View>(R.id.sel_move).setOnClickListener { doMoveBatch() }

        setupFab()
        ensurePermissionThenLoad()
    }

    override fun onResume() {
        super.onResume()
        // 재생 후 복귀 시 최근목록/신규영상 반영
        if (hasMediaAccess()) load()
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
        DurationHub.fetchAsync(this)   // v6: hub 길이맵 1회 채움(길이 불일치 마커용)
        ResolutionHub.fetchAsync(this) // 4: hub 해상도맵 1회 채움(해상도 불일치 마커용)
        Thread {
            val allVids = MediaLibrary.queryVideos(this)
            if (mode == "videos") {
                var vids = LibPrefs.sortVids(this, allVids).filter { LibPrefs.passWatch(this, it.uri.toString()) }
                if (LibPrefs.embedFilter(this)) vids = vids.filter { JEmbed.isUnembedded(this, it.uri, it.path) }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    homeVids = vids.map { it.uri.toString() to it.name }
                    empty.visibility = if (vids.isEmpty()) View.VISIBLE else View.GONE
                    recycler.layoutManager =
                        if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
                    val va = VideoAdapter(vids, grid) { v -> play(v.uri.toString(), v.name) }
                    va.onSelectionChanged = { updateSelBar() }
                    videoAdapter = va
                    recycler.adapter = va
                }
            } else if (mode == "tree") {
                val root = MediaLibrary.treeRoot(allVids)
                val children = MediaLibrary.treeChildren(allVids, root)
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
                    recycler.adapter = TreeAdapter(children, grid,
                        onDir = { e ->
                            startActivity(
                                Intent(this, TreeActivity::class.java)
                                    .putExtra("dir", e.dirPath)
                                    .putExtra("title", e.name)
                            )
                        },
                        onVideo = { e -> e.vid?.let { play(it.uri.toString(), it.name) } }
                    )
                }
            } else {
                // folder
                val folds = LibPrefs.sortFolds(this, MediaLibrary.folders(allVids))
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    empty.visibility = if (folds.isEmpty()) View.VISIBLE else View.GONE
                    recycler.layoutManager =
                        if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
                    recycler.adapter = FolderAdapter(folds, grid) { f ->
                        startActivity(
                            Intent(this, FolderVideosActivity::class.java)
                                .putExtra("path", f.path)
                                .putExtra("name", f.name)
                        )
                    }
                }
            }
        }.start()
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

    // ─── jembed 선택모드/배치 임베드 ───
    private fun enterSelection() {
        val a = videoAdapter
        if (a == null) {
            Toast.makeText(this, "'영상' 보기 모드에서 선택하세요 (빠른설정 → 영상)", Toast.LENGTH_SHORT).show(); return
        }
        a.selectionMode = true; a.notifyDataSetChanged()
        selBar.visibility = View.VISIBLE; updateSelBar()
    }

    private fun exitSelection() {
        videoAdapter?.let { it.selectionMode = false; it.selected.clear(); it.notifyDataSetChanged() }
        selBar.visibility = View.GONE
    }

    private fun updateSelBar() { selCount.text = "${videoAdapter?.selected?.size ?: 0}개 선택" }

    private fun doEmbedBatch() {
        val a = videoAdapter ?: return
        val items = a.selected.toList().mapNotNull { u ->
            val name = homeVids.find { it.first == u }?.second ?: return@mapNotNull null
            val code = Regex("([A-Za-z]{2,7}-\\d{2,5})").find(name)?.value?.uppercase() ?: return@mapNotNull null
            Uri.parse(u) to code
        }
        if (items.isEmpty()) { Toast.makeText(this, "선택 없음 / 품번 추출 실패", Toast.LENGTH_SHORT).show(); return }
        val ll = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 32, 48, 16)
        }
        val tv = TextView(this)
        val pb = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        ll.addView(tv); ll.addView(pb)
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("임베드 중 (${items.size}개)").setView(ll).setCancelable(false).create()
        dlg.show()
        JEmbed.embedBatch(this, items,
            onProgress = { idx, total, code, stage, pct ->
                tv.text = "${idx + 1}/$total   $code   $stage   $pct%"
                pb.progress = if (stage == "remux") pct else if (stage == "embed") 100 else 0
            },
            onDone = { ok, fail, fails ->
                dlg.dismiss(); exitSelection(); load()
                val msg = "완료: 성공 $ok, 실패 $fail" +
                    if (fails.isNotEmpty()) "\n" + fails.take(3).joinToString("\n") else ""
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            })
    }

    private fun doMoveBatch() {
        val a = videoAdapter ?: return
        val items = a.selected.toList().mapNotNull { u -> homeVids.find { it.first == u }?.let { Uri.parse(u) to it.second } }
        if (items.isEmpty()) { Toast.makeText(this, "선택 없음", Toast.LENGTH_SHORT).show(); return }
        pickFolder(
            onInternal = { dir -> runMoveProgress(items) { onP, onD -> JMove.move(this, items, dir, onP, onD) } },
            onSaf = { doc -> runMoveProgress(items) { onP, onD -> JMove.moveToSaf(this, items, doc, onP, onD) } }
        )
    }

    private fun runMoveProgress(
        items: List<Pair<Uri, String>>,
        mover: ((Int, Int, String) -> Unit, (Int, Int, List<String>) -> Unit) -> Unit
    ) {
        val ll = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val tv = TextView(this)
        val pb = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = items.size }
        ll.addView(tv); ll.addView(pb)
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("이동 중 (${items.size}개)").setView(ll).setCancelable(false).create()
        dlg.show()
        mover({ idx, _, name -> tv.text = "${idx + 1}/${items.size}   $name"; pb.progress = idx },
            { ok, fail, fails ->
                dlg.dismiss(); exitSelection(); load()
                val msg = "이동 완료: 성공 $ok, 실패 $fail" +
                    if (fails.isNotEmpty()) "\n" + fails.take(3).joinToString("\n") else ""
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            })
    }

    // 반화면 폴더트리(BottomSheet) — 내부저장소(File) + 외부저장소(SAF 트리) 탐색 후 '여기로 이동'.
    private fun pickFolder(
        onInternal: (String) -> Unit,
        onSaf: (androidx.documentfile.provider.DocumentFile) -> Unit
    ) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val outer = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val pathTv = TextView(this).apply { setPadding(8, 8, 8, 16); textSize = 13f }
        val scroll = android.widget.ScrollView(this)
        val listLl = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        scroll.addView(listLl)
        val moveBtn = com.google.android.material.button.MaterialButton(this).apply { text = "여기로 이동" }
        var mode = 0
        var curFile: java.io.File? = null
        var curDoc: androidx.documentfile.provider.DocumentFile? = null
        fun addRow(text: String, onClick: () -> Unit) {
            listLl.addView(TextView(this@MediaLibraryActivity).apply {
                this.text = text; textSize = 15f; setPadding(8, 28, 8, 28); setOnClickListener { onClick() }
            })
        }
        fun render() {
            listLl.removeAllViews()
            when (mode) {
                0 -> {
                    pathTv.text = "대상 저장소 선택"; moveBtn.visibility = View.GONE
                    addRow("📁  내부저장소") { mode = 1; curFile = Environment.getExternalStorageDirectory(); render() }
                    for (t in SafTrees.all(this@MediaLibraryActivity)) {
                        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@MediaLibraryActivity, Uri.parse(t))
                        if (doc != null) addRow("💾  ${doc.name ?: "외부저장소"}") { mode = 2; curDoc = doc; render() }
                    }
                }
                1 -> {
                    val cur = curFile!!; pathTv.text = cur.absolutePath; moveBtn.visibility = View.VISIBLE
                    addRow("⬆  ..") { val p = cur.parentFile; if (p != null) curFile = p else mode = 0; render() }
                    cur.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { d ->
                        addRow("📁  ${d.name}") { curFile = d; render() }
                    }
                }
                2 -> {
                    val cur = curDoc!!; pathTv.text = cur.name ?: "외부저장소"; moveBtn.visibility = View.VISIBLE
                    addRow("⬆  ..") { val p = cur.parentFile; if (p != null) curDoc = p else mode = 0; render() }
                    cur.listFiles().filter { it.isDirectory }.sortedBy { (it.name ?: "").lowercase() }.forEach { d ->
                        addRow("📁  ${d.name}") { curDoc = d; render() }
                    }
                }
            }
        }
        moveBtn.setOnClickListener {
            sheet.dismiss()
            when (mode) { 1 -> onInternal(curFile!!.absolutePath); 2 -> onSaf(curDoc!!) }
        }
        outer.addView(pathTv)
        outer.addView(scroll, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(moveBtn)
        render(); sheet.setContentView(outer); sheet.show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (videoAdapter?.selectionMode == true) exitSelection() else super.onBackPressed()
    }
}
