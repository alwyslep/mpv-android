package `is`.xyz.mpv

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

// 55b: 툴바 아이콘 순서 설정 — ▲▼ 버튼(확실) + ≡ 핸들/롱프레스 드래그로 재배치, prefs 저장(onPause).
//   ALL=캐논(id 고정), 순서만 사용자 정의. 솔리드 배경+흰색 큰 버튼으로 가시성 확보.
class ToolbarOrderActivity : AppCompatActivity() {
    private val keys = ArrayList<String>()
    private lateinit var adapter: Adapter
    private lateinit var touchHelper: ItemTouchHelper

    private fun dp(v: Float) = Utils.convertDp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys.clear(); keys.addAll(LibToolbar.order(this))
        JavDiag.log("order", "onCreate keys=${keys.size} [${keys.joinToString(",")}]")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12161C.toInt())   // 솔리드 다크 — 오로라 위 가시성 보장
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "툴바 아이콘 순서"; setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationOnClickListener { finish() }
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_arrow_back)
                ?: navigationIcon
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "▲▼ 버튼으로 옮기거나, ≡ 핸들/길게 눌러 끌어 순서를 바꾸세요."
            textSize = 12f; setPadding(dp(16f), dp(8f), dp(16f), dp(8f)); setTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(hint)

        val recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ToolbarOrderActivity) }
        adapter = Adapter()
        recycler.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(recycler)   // adapter 설정 후 attach
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val reset = MaterialButton(this).apply {
            text = "기본값으로 복원"
            setOnClickListener {
                LibToolbar.resetOrder(this@ToolbarOrderActivity)
                keys.clear(); keys.addAll(LibToolbar.order(this@ToolbarOrderActivity))
                adapter.notifyDataSetChanged()
            }
        }
        root.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16f), dp(8f), dp(16f), dp(16f))
        })

        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        LibToolbar.setOrder(this, keys)
        JavDiag.log("order", "save [${keys.joinToString(",")}]")
    }

    inner class VH(row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val icon = row.getChildAt(0) as ImageView
        val label = row.getChildAt(1) as TextView
        val up = row.getChildAt(2) as TextView
        val down = row.getChildAt(3) as TextView
        val handle = row.getChildAt(4) as TextView
    }

    inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            fun btn(c: String) = TextView(ctx).apply {
                text = c; textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
                width = dp(44f); setPadding(0, dp(8f), 0, dp(8f)); isClickable = true; isFocusable = true
                background = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(0x33FFFFFF) }
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(12f), dp(12f), dp(12f))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)) }
            val label = TextView(ctx).apply {
                textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(16f), 0, dp(8f), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val up = btn("▲").apply { layoutParams = LinearLayout.LayoutParams(dp(44f), ViewGroup.LayoutParams.WRAP_CONTENT) }
            val down = btn("▼").apply { layoutParams = LinearLayout.LayoutParams(dp(44f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) } }
            val handle = TextView(ctx).apply {
                text = "≡"; textSize = 24f; setTextColor(0xFFBBBBBB.toInt()); setPadding(dp(14f), dp(6f), dp(8f), dp(6f))
            }
            row.addView(icon); row.addView(label); row.addView(up); row.addView(down); row.addView(handle)
            return VH(row)
        }

        override fun getItemCount() = keys.size

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: VH, position: Int) {
            val spec = LibToolbar.ALL.firstOrNull { it.key == keys[position] } ?: return
            JavDiag.log("order", "bind[$position] ${spec.key}")
            h.icon.setImageResource(spec.icon)
            h.icon.setColorFilter(0xFFDDDDDD.toInt())
            h.label.text = spec.label
            h.up.setOnClickListener { val p = h.bindingAdapterPosition; JavDiag.log("order", "▲ tap p=$p"); if (p > 0) move(p, p - 1) }
            h.down.setOnClickListener { val p = h.bindingAdapterPosition; JavDiag.log("order", "▼ tap p=$p"); if (p in 0 until keys.size - 1) move(p, p + 1) }
            h.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { touchHelper.startDrag(h); true } else false
            }
        }

        fun move(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= keys.size || to >= keys.size || from == to) { JavDiag.log("order", "move skip $from→$to"); return }
            val k = keys.removeAt(from); keys.add(to, k); notifyItemMoved(from, to)
            JavDiag.log("order", "move $from→$to ok [${keys.joinToString(",")}]")
        }
    }

    inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = true
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition); return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
    }
}
