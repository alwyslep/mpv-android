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
                val title = (it.lastPathSegment ?: "폴더").substringAfterLast(":").substringAfterLast("/")
                startActivity(
                    Intent(this, SafBrowserActivity::class.java)
                        .putExtra("tree", it.toString())
                        .putExtra("title", title)
                )
            }
        }

    private val reqPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_library)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.menu.add(0, 2, 0, "검색").apply {
            setIcon(R.drawable.ic_search_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 3, 1, "빠른 설정").apply {
            setIcon(R.drawable.ic_tune_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 1, 2, "설정").apply {
            setIcon(R.drawable.ic_settings_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> startActivity(Intent(this, SearchActivity::class.java))
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
                Toast.makeText(this, "폴더 선택기를 열 수 없습니다", Toast.LENGTH_SHORT).show()
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

    private fun load() {
        Thread {
            val vids = MediaLibrary.queryVideos(this)
            val folds = LibPrefs.sortFolds(this, MediaLibrary.folders(vids))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (folds.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    recycler.adapter = null
                } else {
                    empty.visibility = View.GONE
                    recycler.adapter = FolderAdapter(folds) { f ->
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
        Recents.add(this, uri, title)
        val i = if (uri.startsWith("content://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        } else {
            Intent().putExtra("filepath", uri)
        }
        i.setClass(this, MPVActivity::class.java)
        startActivity(i)
    }

    private fun showUrlDialog() {
        val input = EditText(this)
        input.hint = "http(s):// 또는 rtmp:// ..."
        AlertDialog.Builder(this)
            .setTitle("네트워크 스트림 열기")
            .setView(input)
            .setPositiveButton("재생") { _, _ ->
                val u = input.text.toString().trim()
                if (u.isNotEmpty()) play(u, u)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showRecents() {
        val recents = Recents.list(this)
        if (recents.isEmpty()) {
            Toast.makeText(this, "최근 재생 기록이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = recents.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("최근 재생")
            .setItems(titles) { _, which ->
                val (uri, title) = recents[which]
                play(uri, title)
            }
            .show()
    }
}
