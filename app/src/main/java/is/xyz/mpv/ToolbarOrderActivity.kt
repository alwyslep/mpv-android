package `is`.xyz.mpv

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

// 55b: 툴바 아이콘 순서 설정 — 롱프레스 드래그(또는 ≡ 핸들)로 재배치, prefs 저장.
//   ALL 은 캐논(id 고정), 순서만 사용자 정의. 저장은 onPause(나갈 때 자동).
class ToolbarOrderActivity : AppCompatActivity() {
    private val keys = ArrayList<String>()
    private lateinit var adapter: Adapter
    private lateinit var touchHelper: ItemTouchHelper

    private fun dp(v: Float) = Utils.convertDp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuroraDrawable.apply(this)
        Utils.applyRtl(this)
        keys.clear(); keys.addAll(LibToolbar.order(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "툴바 아이콘 순서"
            setNavigationOnClickListener { finish() }
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_arrow_back)
                ?: navigationIcon
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "길게 눌러 끌어 순서를 바꾸세요. (오른쪽 ≡ 핸들로도 드래그)"
            textSize = 12f; setPadding(dp(16f), dp(8f), dp(16f), dp(8f)); setTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(hint)

        val recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ToolbarOrderActivity) }
        adapter = Adapter()
        recycler.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(recycler)
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
        LibToolbar.setOrder(this, keys)   // 나갈 때 저장 → 다음 화면 진입 시 적용
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(1)
        val label: TextView = v.findViewById(2)
        val handle: TextView = v.findViewById(3)
    }

    inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(parent.context).apply { id = 1; layoutParams = LinearLayout.LayoutParams(dp(26f).toInt(), dp(26f).toInt()) }
            val label = TextView(parent.context).apply {
                id = 2; textSize = 15f; setPadding(dp(16f), 0, dp(16f), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val handle = TextView(parent.context).apply { id = 3; text = "≡"; textSize = 22f; setTextColor(0xFF888888.toInt()) }
            row.addView(icon); row.addView(label); row.addView(handle)
            return VH(row)
        }

        override fun getItemCount() = keys.size

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: VH, position: Int) {
            val spec = LibToolbar.ALL.firstOrNull { it.key == keys[position] } ?: return
            h.icon.setImageResource(spec.icon)
            h.icon.setColorFilter(0xFFDDDDDD.toInt())
            h.label.text = spec.label
            h.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                false
            }
        }

        fun move(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= keys.size || to >= keys.size) return
            val k = keys.removeAt(from); keys.add(to, k); notifyItemMoved(from, to)
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
