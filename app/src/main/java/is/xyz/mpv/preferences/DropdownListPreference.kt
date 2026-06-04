package `is`.xyz.mpv.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import `is`.xyz.mpv.R

/**
 * 22: ListPreference 를 인라인 드롭다운(ExposedDropdownMenu)으로 — 팝업 다이얼로그 대신
 * 카드 안에서 바로 펼침. 옵션 많은 항목(스케일러 프리셋)용. entries/entryValues 그대로.
 */
class DropdownListPreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    init {
        layoutResource = R.layout.pref_dropdown
        isSelectable = false
    }

    override fun onClick() {}  // 팝업 안 띄움

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val tv = holder.findViewById(R.id.dropdown_input) as? MaterialAutoCompleteTextView ?: return
        val ents = entries ?: return
        val vals = entryValues ?: return
        tv.setSimpleItems(Array(ents.size) { ents[it].toString() })
        val idx = vals.indexOfFirst { it == value }
        tv.setText(if (idx >= 0) ents[idx] else ents.getOrNull(0) ?: "", false)
        tv.setOnItemClickListener { _, _, pos, _ ->
            val v = vals.getOrNull(pos)?.toString() ?: return@setOnItemClickListener
            if (v != value) value = v
        }
    }
}
