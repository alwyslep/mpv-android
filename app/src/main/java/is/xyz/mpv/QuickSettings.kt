package `is`.xyz.mpv

import android.content.Context
import android.graphics.Typeface
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// NextPlayer식 빠른설정 — 화면 중앙 팝업, 전요소 풀폭 균형정렬. 변경은 '완료'에서 일괄 반영.
object QuickSettings {
    fun show(act: AppCompatActivity, onApply: () -> Unit) {
        val ctx: Context = act
        val v = act.layoutInflater.inflate(R.layout.sheet_quick_settings, null)

        // 보류 상태(완료 전까지 prefs 미반영)
        var pMode = LibPrefs.viewMode(ctx)
        var pGrid = LibPrefs.grid(ctx)
        var pSort = LibPrefs.sortKey(ctx)
        var pAsc = LibPrefs.sortAsc(ctx)
        var pDur = LibPrefs.showDur(ctx)
        var pExt = LibPrefs.showExt(ctx)
        var pPath = LibPrefs.showPath(ctx)
        var pProg = LibPrefs.showProgress(ctx)
        var pRes = LibPrefs.showRes(ctx)
        var pSize = LibPrefs.showSize(ctx)
        var pThumb = LibPrefs.showThumb(ctx)

        // 보기 모드
        val grpMode = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_mode)
        grpMode.check(when (pMode) { "videos" -> R.id.btn_mode_videos; "tree" -> R.id.btn_mode_tree; else -> R.id.btn_mode_folder })
        grpMode.addOnButtonCheckedListener { _, id, on ->
            if (on) pMode = when (id) { R.id.btn_mode_videos -> "videos"; R.id.btn_mode_tree -> "tree"; else -> "folder" }
        }

        // 레이아웃
        val grpLayout = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_layout)
        grpLayout.check(if (pGrid) R.id.btn_grid else R.id.btn_list)
        grpLayout.addOnButtonCheckedListener { _, id, on -> if (on) pGrid = id == R.id.btn_grid }

        // 정렬 아이콘 셀
        val primary = MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimary, 0)
        val variant = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        val cells = listOf(
            "name" to v.findViewById<LinearLayout>(R.id.sc_name),
            "length" to v.findViewById<LinearLayout>(R.id.sc_length),
            "date" to v.findViewById<LinearLayout>(R.id.sc_date),
            "size" to v.findViewById<LinearLayout>(R.id.sc_size),
            "path" to v.findViewById<LinearLayout>(R.id.sc_path)
        )
        fun paint() {
            for ((key, cell) in cells) {
                val sel = key == pSort
                val ic = cell.getChildAt(0) as ImageView
                val lbl = cell.getChildAt(1) as TextView
                ic.setColorFilter(if (sel) primary else variant)
                lbl.setTextColor(if (sel) primary else variant)
                lbl.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        for ((key, cell) in cells) cell.setOnClickListener { pSort = key; paint() }
        paint()

        // 정렬 순서
        val grpOrder = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_order)
        grpOrder.check(if (pAsc) R.id.btn_asc else R.id.btn_desc)
        grpOrder.addOnButtonCheckedListener { _, id, on -> if (on) pAsc = id == R.id.btn_asc }

        // 필드
        bindChip(v.findViewById(R.id.chip_f_dur), pDur) { pDur = it }
        bindChip(v.findViewById(R.id.chip_f_ext), pExt) { pExt = it }
        bindChip(v.findViewById(R.id.chip_f_path), pPath) { pPath = it }
        bindChip(v.findViewById(R.id.chip_f_progress), pProg) { pProg = it }
        bindChip(v.findViewById(R.id.chip_f_res), pRes) { pRes = it }
        bindChip(v.findViewById(R.id.chip_f_size), pSize) { pSize = it }
        bindChip(v.findViewById(R.id.chip_f_thumb), pThumb) { pThumb = it }

        val dlg = MaterialAlertDialogBuilder(act).setView(v).create()

        v.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener { dlg.dismiss() }
        v.findViewById<MaterialButton>(R.id.btn_done).setOnClickListener {
            LibPrefs.setViewMode(ctx, pMode)
            LibPrefs.setGrid(ctx, pGrid)
            LibPrefs.setSort(ctx, pSort, pAsc)
            LibPrefs.setField(ctx, "show_dur", pDur)
            LibPrefs.setField(ctx, "show_ext", pExt)
            LibPrefs.setField(ctx, "show_path", pPath)
            LibPrefs.setField(ctx, "show_progress", pProg)
            LibPrefs.setField(ctx, "show_res", pRes)
            LibPrefs.setField(ctx, "show_size", pSize)
            LibPrefs.setField(ctx, "show_thumb", pThumb)
            dlg.dismiss()
            onApply()
        }
        dlg.show()
    }

    private fun bindChip(chip: Chip, initial: Boolean, onChange: (Boolean) -> Unit) {
        chip.isChecked = initial
        chip.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
