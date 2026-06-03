package `is`.xyz.mpv

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * 상세 필터 시트 — 해상도 상한 / 임베드없음 / 해상도·길이 불일치 / 확장자 + 메타(배우·스튜디오·시리즈·장르).
 * FilterEngine 이 읽는 prefs 와 동일 키. '적용'에서 일괄 저장 후 onApply()로 reload.
 * 메타 목록은 MetaHub(hub /meta-index)에서 — 미수신 시 해당 행은 빈 목록(안내 토스트).
 */
object FilterSheet {
    private const val PREFS = "media_library"
    private val EXTS = listOf("MP4", "MKV", "TS", "AVI", "WMV", "MOV", "M4V")
    private val RES = listOf(0 to "전체", 2160 to "≤2160p", 1080 to "≤1080p", 720 to "≤720p", 480 to "≤480p")

    fun show(act: AppCompatActivity, onApply: () -> Unit) {
        val ctx: Context = act
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        MetaHub.fetchAsync(ctx)

        val ll = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        fun header(t: String) = ll.addView(TextView(ctx).apply {
            text = t; setPadding(0, 28, 0, 6); textSize = 13f; setTypeface(null, Typeface.BOLD)
        })

        header("해상도 상한")
        var curRes = p.getInt("filter_res_max", 0)
        val resGrp = ChipGroup(ctx).apply { isSingleSelection = true }
        RES.forEach { (vv, label) ->
            resGrp.addView(Chip(ctx).apply {
                text = label; isCheckable = true; isChecked = vv == curRes
                setOnClickListener { curRes = vv }
            })
        }
        ll.addView(resGrp)

        header("상태")
        val cbEmbed = CheckBox(ctx).apply { text = "임베드 없음만"; isChecked = p.getBoolean("embed_filter", false) }
        val cbMisR = CheckBox(ctx).apply { text = "해상도 불일치 (저화질/부분)"; isChecked = p.getBoolean("filter_mismatch_res", false) }
        val cbMisD = CheckBox(ctx).apply { text = "길이 불일치 (부분 다운)"; isChecked = p.getBoolean("filter_mismatch_dur", false) }
        ll.addView(cbEmbed); ll.addView(cbMisR); ll.addView(cbMisD)

        header("확장자")
        val curExt = (p.getStringSet("filter_ext", emptySet()) ?: emptySet()).toMutableSet()
        val extGrp = ChipGroup(ctx)
        EXTS.forEach { e ->
            extGrp.addView(Chip(ctx).apply {
                text = e; isCheckable = true; isChecked = e in curExt
                setOnCheckedChangeListener { _, c -> if (c) curExt.add(e) else curExt.remove(e) }
            })
        }
        ll.addView(extGrp)

        header("메타 (hub 메타 필요 · 품번으로 매칭)")
        val fa = (p.getStringSet("filter_actress", emptySet()) ?: emptySet()).toMutableSet()
        val fs = (p.getStringSet("filter_studio", emptySet()) ?: emptySet()).toMutableSet()
        val fse = (p.getStringSet("filter_series", emptySet()) ?: emptySet()).toMutableSet()
        val fg = (p.getStringSet("filter_genre", emptySet()) ?: emptySet()).toMutableSet()
        ll.addView(metaRow(ctx, "배우", fa) { MetaHub.actresses })
        ll.addView(metaRow(ctx, "스튜디오", fs) { MetaHub.studios })
        ll.addView(metaRow(ctx, "시리즈", fse) { MetaHub.seriesList })
        ll.addView(metaRow(ctx, "장르", fg) { MetaHub.genres })

        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 28, 0, 0) }
        val reset = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "초기화" }
        val apply = MaterialButton(ctx).apply {
            text = "적용"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btns.addView(reset); btns.addView(apply)

        // 버튼은 스크롤 밖 하단 고정 (콘텐츠가 길어 잘리던 문제) — 스크롤 영역 + 고정 버튼바
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(ctx).apply { addView(ll) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(btns)
        }
        val sheet = BottomSheetDialog(ctx)
        sheet.setContentView(outer)

        reset.setOnClickListener {
            p.edit().remove("filter_res_max").putBoolean("embed_filter", false)
                .putBoolean("filter_mismatch_res", false).putBoolean("filter_mismatch_dur", false)
                .remove("filter_ext").remove("filter_actress").remove("filter_studio")
                .remove("filter_series").remove("filter_genre").apply()
            sheet.dismiss(); onApply()
        }
        apply.setOnClickListener {
            p.edit()
                .putInt("filter_res_max", curRes)
                .putBoolean("embed_filter", cbEmbed.isChecked)
                .putBoolean("filter_mismatch_res", cbMisR.isChecked)
                .putBoolean("filter_mismatch_dur", cbMisD.isChecked)
                .putStringSet("filter_ext", curExt)
                .putStringSet("filter_actress", fa)
                .putStringSet("filter_studio", fs)
                .putStringSet("filter_series", fse)
                .putStringSet("filter_genre", fg)
                .apply()
            sheet.dismiss(); onApply()
            Toast.makeText(ctx, "필터 적용됨", Toast.LENGTH_SHORT).show()
        }
        sheet.show()
        // 콘텐츠 전체가 보이도록 펼친 상태로 — 적용/초기화 버튼 항상 노출
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
    }

    // "배우  (N)  ▸" 행 — 탭하면 hub 목록 다중선택 다이얼로그. 선택 set 직접 갱신.
    private fun metaRow(ctx: Context, label: String, sel: MutableSet<String>, options: () -> List<String>): TextView {
        val tv = TextView(ctx).apply { textSize = 15f; setPadding(8, 26, 8, 26) }
        fun render() { tv.text = if (sel.isEmpty()) "$label   ▸" else "$label   (${sel.size})   ▸" }
        render()
        tv.setOnClickListener {
            val all = options()
            if (all.isEmpty()) {
                Toast.makeText(ctx, "$label 목록 없음 (hub 메타 미수신)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val items = all.toTypedArray()
            val checked = BooleanArray(items.size) { items[it] in sel }
            AlertDialog.Builder(ctx).setTitle(label)
                .setMultiChoiceItems(items, checked) { _, which, isC -> if (isC) sel.add(items[which]) else sel.remove(items[which]) }
                .setPositiveButton("확인") { _, _ -> render() }
                .setNeutralButton("선택해제") { _, _ -> sel.clear(); render() }
                .setNegativeButton("취소", null)
                .show()
        }
        return tv
    }
}
