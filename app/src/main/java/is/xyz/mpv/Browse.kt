package `is`.xyz.mpv

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup

// P2 (JAV 메타, 로드맵 B): 배우/스튜디오/시리즈별 뷰.
//   디스크 캐시(=브라우징된 영상)의 임베드 메타를 그룹핑. 전수 MMR 스캔 없음(누적형).
class BrowseActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var toolbar: MaterialToolbar
    private var all: List<CachedVideo> = emptyList()
    private var dim = "actor"        // actor | studio | series
    private var inVideos = false

    private var pendingUri: String? = null
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            pendingUri?.let { Playback.onResult(this, it, res.data) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        recycler = findViewById(R.id.recycler)
        status = findViewById(R.id.status)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBack() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        val grp = findViewById<MaterialButtonToggleGroup>(R.id.grp_dim)
        grp.check(R.id.dim_actor)
        grp.addOnButtonCheckedListener { _, id, on ->
            if (!on) return@addOnButtonCheckedListener
            dim = when (id) { R.id.dim_studio -> "studio"; R.id.dim_series -> "series"; else -> "actor" }
            inVideos = false
            showNames()
        }

        Thread {
            val list = ThumbLoader.readAllCachedMeta(this)
            runOnUiThread { if (!isFinishing) { all = list; showNames() } }
        }.start()
    }

    private fun onBack() {
        if (inVideos) { inVideos = false; showNames() } else finish()
    }

    private fun keyOf(c: CachedVideo): String = when (dim) {
        "studio" -> c.studio
        "series" -> c.series
        else -> c.artist
    }

    private fun showNames() {
        inVideos = false
        toolbar.title = when (dim) { "studio" -> "스튜디오"; "series" -> "시리즈"; else -> "배우" }
        val groups = LinkedHashMap<String, Int>()
        for (c in all) {
            val k = keyOf(c).trim()
            if (k.isEmpty()) continue
            groups[k] = (groups[k] ?: 0) + 1
        }
        val names = groups.entries.sortedByDescending { it.value }
            .map { it.key to it.value }
        status.text = if (all.isEmpty())
            "아직 캐시된 메타 없음 — 영상을 둘러보면 채워집니다"
        else "${names.size}개 · 본 작품 ${all.size}개 기준"
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = NameAdapter(names) { name -> showVideos(name) }
    }

    private fun showVideos(name: String) {
        inVideos = true
        toolbar.title = name
        val items = all.filter { keyOf(it).trim() == name }.map {
            SearchItem(it.code.ifEmpty { it.title }, Uri.parse(it.uri), "", it.dur ?: 0L, 0L)
        }
        status.text = "${items.size}개"
        val grid = LibPrefs.grid(this)
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, maxOf(2, resources.configuration.screenWidthDp / 170))
            else LinearLayoutManager(this)
        recycler.adapter = SearchAdapter(items, grid) { item ->
            pendingUri = item.uri.toString()
            playLauncher.launch(Playback.intentFor(this, item.uri.toString(), item.name))
        }
    }

    class NameAdapter(
        private val items: List<Pair<String, Int>>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<NameAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val count: TextView = v.findViewById(R.id.count)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_name_count, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) {
            val (n, c) = items[position]
            h.name.text = n
            h.count.text = c.toString()
            h.itemView.setOnClickListener { onClick(n) }
        }
    }
}
