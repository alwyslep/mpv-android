package `is`.xyz.mpv.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import `is`.xyz.mpv.R

/**
 * 21: ListPreference 를 인라인 토글그룹(세그먼트)으로 — 팝업 대신 버튼 N선택.
 * entries/entryValues(ListPreference attr) 그대로 사용. 빠른설정 토글그룹과 같은 UX.
 */
open class SegmentListPreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    init {
        layoutResource = R.layout.pref_segment
        isSelectable = false   // 행 클릭(팝업) 비활성 — 버튼으로만
    }

    override fun onClick() {}  // 팝업 안 띄움

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val group = holder.findViewById(R.id.segment_group) as? MaterialButtonToggleGroup ?: return
        group.removeAllViews()
        val vals = entryValues ?: return
        val ents = entries ?: return
        val cur = value
        val idMap = HashMap<Int, String>()
        for (i in vals.indices) {
            val v = vals[i].toString()
            val btn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = ents.getOrNull(i) ?: v
                id = View.generateViewId()
                isCheckable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            idMap[btn.id] = v
            group.addView(btn)
            if (v == cur) group.check(btn.id)
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) idMap[checkedId]?.let { if (it != value) value = it }
        }
    }
}
