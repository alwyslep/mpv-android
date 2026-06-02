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

    // 로컬/USB 폴더 1개 선택 → 내 SAF 타일 브라우저로 진입(OS 선택기 대신).
    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> startActivity(Intent(this, SearchActivity::class.java))
                4 -> startActivity(Intent(this, BrowseActivity::class.java))
                3 -> QuickSettings.show(this) { load() }
                1 -> startActivity(Intent(this, `is`.xyz.mpv.preferences.PreferenceActivity::class.java))
            }
            true
        }

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        empty = findViewById(R.id.empty)

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

    private fun spanCount(): Int = maxOf(2, resources.configuration.screenWidthDp / 170)

    private fun load() {
        val mode = LibPrefs.viewMode(this)
        val grid = LibPrefs.grid(this)
        Thread {
            val allVids = MediaLibrary.queryVideos(this)
            if (mode == "videos") {
                val vids = LibPrefs.sortVids(this, allVids).filter { LibPrefs.passWatch(this, it.uri.toString()) }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    homeVids = vids.map { it.uri.toString() to it.name }
                    empty.visibility = if (vids.isEmpty()) View.VISIBLE else View.GONE
                    recycler.layoutManager =
                        if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
                    recycler.adapter = VideoAdapter(vids, grid) { v -> play(v.uri.toString(), v.name) }
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
}
