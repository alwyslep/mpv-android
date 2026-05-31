package `is`.xyz.mpv

import android.content.Context
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup

// NextPlayer식 빠른설정(⊞) 바텀시트 — 레이아웃/정렬/필드. prefs(LibPrefs) 저장, 닫으면 onApply.
object QuickSettings {
    fun show(act: AppCompatActivity, onApply: () -> Unit) {
        val ctx: Context = act
        val dlg = BottomSheetDialog(act)
        val v = act.layoutInflater.inflate(R.layout.sheet_quick_settings, null)
        dlg.setContentView(v)

        val grpLayout = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_layout)
        grpLayout.check(if (LibPrefs.grid(ctx)) R.id.btn_grid else R.id.btn_list)
        grpLayout.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) LibPrefs.setGrid(ctx, checkedId == R.id.btn_grid)
        }

        val grpSort = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_sort)
        grpSort.check(
            when (LibPrefs.sortKey(ctx)) {
                "date" -> R.id.btn_sort_date
                "size" -> R.id.btn_sort_size
                else -> R.id.btn_sort_name
            }
        )
        grpSort.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val key = when (checkedId) {
                    R.id.btn_sort_date -> "date"
                    R.id.btn_sort_size -> "size"
                    else -> "name"
                }
                LibPrefs.setSort(ctx, key, LibPrefs.sortAsc(ctx))
            }
        }

        val grpOrder = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_order)
        grpOrder.check(if (LibPrefs.sortAsc(ctx)) R.id.btn_asc else R.id.btn_desc)
        grpOrder.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) LibPrefs.setSort(ctx, LibPrefs.sortKey(ctx), checkedId == R.id.btn_asc)
        }

        bindCheck(v.findViewById(R.id.cb_path), LibPrefs.showPath(ctx)) { LibPrefs.setField(ctx, "show_path", it) }
        bindCheck(v.findViewById(R.id.cb_size), LibPrefs.showSize(ctx)) { LibPrefs.setField(ctx, "show_size", it) }
        bindCheck(v.findViewById(R.id.cb_res), LibPrefs.showRes(ctx)) { LibPrefs.setField(ctx, "show_res", it) }
        bindCheck(v.findViewById(R.id.cb_dur), LibPrefs.showDur(ctx)) { LibPrefs.setField(ctx, "show_dur", it) }

        dlg.setOnDismissListener { onApply() }
        dlg.show()
    }

    private fun bindCheck(cb: CheckBox, initial: Boolean, onChange: (Boolean) -> Unit) {
        cb.isChecked = initial
        cb.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
