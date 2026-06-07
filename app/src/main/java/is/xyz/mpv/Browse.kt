package `is`.xyz.mpv

import android.content.Context
import android.content.Intent
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
            if (u != null && Playback.shouldAdvance(this, u, browseItems.find { it.uri.toString() == u }?.name) && playIndex + 1 in browseItems.indices) {
                playBrowse(browseItems[playIndex + 1])
            }
        }
    private val scanPool = Executors.newFixedThreadPool(4)
    @Volatile private var dead = false
    @Volatile private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        Utils.applyRtl(this)   // 58: RTL 레이아웃 토글
        recycler = findViewById(R.id.recycler)
        status = findViewById(R.id.status)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBack() }
        toolbar.menu.add(0, 1, 0, getString(R.string.scan_all)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener { if (it.itemId == 1) fullScan(); true }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        // 57b: 상세보기 칩 딥링크 — dim/name 받으면 로드 후 바로 그 엔티티 작품목록으로.
        val deepDim = intent.getStringExtra("browse_dim")
        val deepName = intent.getStringExtra("browse_name")
        if (deepDim != null) dim = deepDim

        val grp = findViewById<MaterialButtonToggleGroup>(R.id.grp_dim)
        grp.check(when (dim) { "studio" -> R.id.dim_studio; "series" -> R.id.dim_series; "genre" -> R.id.dim_genre; else -> R.id.dim_actor })
        grp.addOnButtonCheckedListener { _, id, on ->
            if (!on) return@addOnButtonCheckedListener
            dim = when (id) { R.id.dim_studio -> "studio"; R.id.dim_series -> "series"; R.id.dim_genre -> "genre"; else -> "actor" }
            inVideos = false
            showNames()
        }

        Thread {
            val list = ThumbLoader.readAllCachedMeta(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                all = list
                if (deepName != null) showVideos(deepName) else showNames()
            }
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
        status.text = getString(R.string.scan_preparing)
        Thread {
            val items = SearchIndex.cached() ?: SearchIndex.build(this)
            val total = items.size
            if (total == 0) {
                runOnUiThread { if (!isFinishing) { status.text = getString(R.string.scan_none); scanning = false } }
                return@Thread
            }
            val done = AtomicInteger(0)
            val latch = CountDownLatch(total)
            for (it in items) {
                scanPool.execute {
                    if (!dead) try { ThumbLoader.indexMeta(this, it.uri, it.name) } catch (_: Throwable) {}
                    val d = done.incrementAndGet()
                    if (d % 25 == 0 || d == total)
                        runOnUiThread { if (!isFinishing) status.text = getString(R.string.scan_progress, d, total) }
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
        "genre" -> c.genre
        else -> c.artist
    }
    // 57: 원자화 — ©ART/aART/©alb 는 jav_dl·JEmbed 가 ", " 로 join(다중 배우 등). 개별 엔티티로 분해해
    //     한 작품이 각 배우(스튜디오/시리즈)에 재연결되도록. studio/series 는 단일값이라 실질 no-op.
    private fun keysOf(c: CachedVideo): List<String> =
        keyOf(c).split(", ").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun showNames() {
        inVideos = false
        toolbar.title = when (dim) { "studio" -> getString(R.string.lbl_studio); "series" -> getString(R.string.lbl_series); "genre" -> getString(R.string.lbl_genre); else -> getString(R.string.lbl_actress) }
        val groups = LinkedHashMap<String, Int>()
        for (c in all) {
            for (k in keysOf(c)) groups[k] = (groups[k] ?: 0) + 1  // 57: 작품을 각 엔티티에 재연결(원자화)
        }
        val names = groups.entries.sortedByDescending { it.value }
            .map { it.key to it.value }
        status.text = if (all.isEmpty())
            getString(R.string.browse_empty)
        else getString(R.string.browse_count, names.size, all.size)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = NameAdapter(names) { name -> showVideos(name) }
    }

    private fun showVideos(name: String) {
        inVideos = true
        toolbar.title = name
        val items = all.filter { name in keysOf(it) && LibPrefs.passWatch(this, it.uri, it.code) }.map {  // 57: 원자화 매칭
            SearchItem(it.code.ifEmpty { it.title }, Uri.parse(it.uri), "", it.dur ?: 0L, 0L)
        }
        browseItems = items
        status.text = getString(R.string.count_items, items.size)
        val grid = LibPrefs.grid(this)
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, LibPrefs.spanCount(this))
            else LinearLayoutManager(this)
        recycler.adapter = SearchAdapter(items, grid) { item -> playBrowse(item) }
    }

    private fun playBrowse(item: SearchItem) {
        playIndex = browseItems.indexOfFirst { it.uri == item.uri }
        pendingUri = item.uri.toString()
        playLauncher.launch(Playback.intentFor(this, item.uri.toString(), item.name))
    }

    companion object {
        // 57b: 상세보기 칩 클릭 → 그 배우/장르/스튜디오/시리즈의 모든 작품 목록으로 진입.
        fun open(ctx: Context, dim: String, name: String) {
            ctx.startActivity(
                Intent(ctx, BrowseActivity::class.java)
                    .putExtra("browse_dim", dim)
                    .putExtra("browse_name", name)
            )
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
