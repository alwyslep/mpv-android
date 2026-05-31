package `is`.xyz.mpv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

// NextPlayer식 빠른설정(⊞) 바텀시트 — 보기모드/레이아웃/정렬/필드. prefs(LibPrefs) 저장, 닫으면 onApply.
object QuickSettings {
    fun show(act: AppCompatActivity, onApply: () -> Unit) {
        val ctx: Context = act
        val dlg = BottomSheetDialog(act)
        val v = act.layoutInflater.inflate(R.layout.sheet_quick_settings, null)
        dlg.setContentView(v)

        // 미디어 보기 모드
        val grpMode = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_mode)
        grpMode.check(
            when (LibPrefs.viewMode(ctx)) {
                "videos" -> R.id.btn_mode_videos
                "tree" -> R.id.btn_mode_tree
                else -> R.id.btn_mode_folder
            }
        )
        grpMode.addOnButtonCheckedListener { _, id, on ->
            if (on) LibPrefs.setViewMode(
                ctx, when (id) {
                    R.id.btn_mode_videos -> "videos"
                    R.id.btn_mode_tree -> "tree"
                    else -> "folder"
                }
            )
        }

        // 미디어 레이아웃
        val grpLayout = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_layout)
        grpLayout.check(if (LibPrefs.grid(ctx)) R.id.btn_grid else R.id.btn_list)
        grpLayout.addOnButtonCheckedListener { _, id, on ->
            if (on) LibPrefs.setGrid(ctx, id == R.id.btn_grid)
        }

        // 정렬 대상(칩)
        val grpSort = v.findViewById<ChipGroup>(R.id.grp_sort)
        grpSort.check(
            when (LibPrefs.sortKey(ctx)) {
                "length" -> R.id.chip_sort_length
                "date" -> R.id.chip_sort_date
                "size" -> R.id.chip_sort_size
                "path" -> R.id.chip_sort_path
                else -> R.id.chip_sort_name
            }
        )
        grpSort.setOnCheckedStateChangeListener { _, ids ->
            val key = when (ids.firstOrNull()) {
                R.id.chip_sort_length -> "length"
                R.id.chip_sort_date -> "date"
                R.id.chip_sort_size -> "size"
                R.id.chip_sort_path -> "path"
                else -> "name"
            }
            LibPrefs.setSort(ctx, key, LibPrefs.sortAsc(ctx))
        }

        // 정렬 순서
        val grpOrder = v.findViewById<MaterialButtonToggleGroup>(R.id.grp_order)
        grpOrder.check(if (LibPrefs.sortAsc(ctx)) R.id.btn_asc else R.id.btn_desc)
        grpOrder.addOnButtonCheckedListener { _, id, on ->
            if (on) LibPrefs.setSort(ctx, LibPrefs.sortKey(ctx), id == R.id.btn_asc)
        }

        // 필드(칩, 다중)
        bindChip(v.findViewById(R.id.chip_f_dur), LibPrefs.showDur(ctx)) { LibPrefs.setField(ctx, "show_dur", it) }
        bindChip(v.findViewById(R.id.chip_f_ext), LibPrefs.showExt(ctx)) { LibPrefs.setField(ctx, "show_ext", it) }
        bindChip(v.findViewById(R.id.chip_f_path), LibPrefs.showPath(ctx)) { LibPrefs.setField(ctx, "show_path", it) }
        bindChip(v.findViewById(R.id.chip_f_progress), LibPrefs.showProgress(ctx)) { LibPrefs.setField(ctx, "show_progress", it) }
        bindChip(v.findViewById(R.id.chip_f_res), LibPrefs.showRes(ctx)) { LibPrefs.setField(ctx, "show_res", it) }
        bindChip(v.findViewById(R.id.chip_f_size), LibPrefs.showSize(ctx)) { LibPrefs.setField(ctx, "show_size", it) }
        bindChip(v.findViewById(R.id.chip_f_thumb), LibPrefs.showThumb(ctx)) { LibPrefs.setField(ctx, "show_thumb", it) }

        dlg.setOnDismissListener { onApply() }
        dlg.show()
    }

    private fun bindChip(chip: Chip, initial: Boolean, onChange: (Boolean) -> Unit) {
        chip.isChecked = initial
        chip.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
