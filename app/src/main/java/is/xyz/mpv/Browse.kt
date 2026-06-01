package `is`.xyz.mpv

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
    private var browseItems: List<SearchItem> = emptyList()
    private var playIndex = -1
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            if (u != null && Playback.shouldAdvance(this, u) && playIndex + 1 in browseItems.indices) {
                playBrowse(browseItems[playIndex + 1])
            }
        }
    private val scanPool = Executors.newFixedThreadPool(4)
    @Volatile private var dead = false
    @Volatile private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        recycler = findViewById(R.id.recycler)
        status = findViewById(R.id.status)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBack() }
        toolbar.menu.add(0, 1, 0, "전체 스캔").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener { if (it.itemId == 1) fullScan(); true }
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

    override fun onDestroy() {
        dead = true
        scanPool.shutdownNow()
        super.onDestroy()
    }

    // 전체 스캔 — 라이브러리 전체(내부+저장 SAF 트리) 메타를 MMR 일괄 인덱싱(병렬 4, 진행률).
    //   커버 없이 메타만(빠름) + 디스크 캐시라 1회성. 누적 캐시가 채워져 모든 작품이 분류에 잡힘.
    private fun fullScan() {
        if (scanning) return
        scanning = true
        status.text = "전체 스캔 준비…"
        Thread {
            val items = SearchIndex.cached() ?: SearchIndex.build(this)
            val total = items.size
            if (total == 0) {
                runOnUiThread { if (!isFinishing) { status.text = "스캔할 영상 없음"; scanning = false } }
                return@Thread
            }
            val done = AtomicInteger(0)
            val latch = CountDownLatch(total)
            for (it in items) {
                scanPool.execute {
                    if (!dead) try { ThumbLoader.indexMeta(this, it.uri, it.name) } catch (_: Throwable) {}
                    val d = done.incrementAndGet()
                    if (d % 25 == 0 || d == total)
                        runOnUiThread { if (!isFinishing) status.text = "전체 스캔 $d/$total" }
                    latch.countDown()
                }
            }
            latch.await()
            if (dead) return@Thread
            all = ThumbLoader.readAllCachedMeta(this)
            runOnUiThread { if (!isFinishing) { scanning = false; showNames() } }
        }.start()
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
        val items = all.filter { keyOf(it).trim() == name && LibPrefs.passWatch(this, it.uri) }.map {
            SearchItem(it.code.ifEmpty { it.title }, Uri.parse(it.uri), "", it.dur ?: 0L, 0L)
        }
        browseItems = items
        status.text = "${items.size}개"
        val grid = LibPrefs.grid(this)
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, maxOf(2, resources.configuration.screenWidthDp / 170))
            else LinearLayoutManager(this)
        recycler.adapter = SearchAdapter(items, grid) { item -> playBrowse(item) }
    }

    private fun playBrowse(item: SearchItem) {
        playIndex = browseItems.indexOfFirst { it.uri == item.uri }
        pendingUri = item.uri.toString()
        playLauncher.launch(Playback.intentFor(this, item.uri.toString(), item.name))
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
