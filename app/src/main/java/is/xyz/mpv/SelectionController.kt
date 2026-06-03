package `is`.xyz.mpv

import android.net.Uri
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** 선택모드를 지원하는 어댑터 공통 계약 — VideoAdapter·TreeAdapter 가 구현. */
interface SelectableVids {
    var selectionMode: Boolean
    val selected: LinkedHashSet<String>
    var onSelectionChanged: (() -> Unit)?
    fun selectableVids(): List<Vid>   // uri→Vid lookup 용 (영상만)
    fun refreshSelection()            // notifyDataSetChanged
}

/**
 * jembed/이동 선택모드 공통 컨트롤러 — MediaLibraryActivity·FolderVideosActivity·TreeActivity 가 공유.
 * 화면이 어댑터를 만들 때 bind(), sel_bar 버튼을 enter/embedBatch/moveBatch/exit 에 연결.
 * 품번/이름은 어댑터의 selectableVids(Vid)에서 직접 추출 — 각 화면의 별도 목록 의존 제거.
 */
class SelectionController(
    private val act: AppCompatActivity,
    private val selBar: View,
    private val selCount: TextView,
    private val onReload: () -> Unit
) {
    var sel: SelectableVids? = null
        private set

    fun bind(a: SelectableVids) { sel = a; a.onSelectionChanged = { update() } }
    fun unbind() { sel = null }

    val isActive: Boolean get() = sel?.selectionMode == true

    // 임베드/이동 독립 진입 — 액션에 맞는 버튼만 노출(임베드 진입=임베드만, 이동 진입=이동만).
    fun enter(showEmbed: Boolean = true, showMove: Boolean = true) {
        val a = sel
        if (a == null) { toast("'영상' 보기 모드 또는 폴더 안에서 선택하세요"); return }
        a.selectionMode = true; a.refreshSelection()
        selBar.findViewById<View>(R.id.sel_embed)?.visibility = if (showEmbed) View.VISIBLE else View.GONE
        selBar.findViewById<View>(R.id.sel_move)?.visibility = if (showMove) View.VISIBLE else View.GONE
        selBar.visibility = View.VISIBLE; update()
    }

    fun exit() {
        sel?.let { it.selectionMode = false; it.selected.clear(); it.refreshSelection() }
        selBar.visibility = View.GONE
    }

    // 전체선택 토글 — 이미 전부 선택돼 있으면 해제. (필터된 목록 전체를 한 번에)
    fun selectAll() {
        val a = sel ?: run { toast("'영상' 보기 모드 또는 폴더 안에서 선택하세요"); return }
        if (!a.selectionMode) { a.selectionMode = true; selBar.visibility = View.VISIBLE }
        val all = a.selectableVids().map { it.uri.toString() }
        if (a.selected.size == all.size && a.selected.containsAll(all)) a.selected.clear()
        else { a.selected.clear(); a.selected.addAll(all) }
        a.refreshSelection(); update()
    }

    fun update() { selCount.text = "${sel?.selected?.size ?: 0}개 선택" }

    private fun vidOf(u: String): Vid? = sel?.selectableVids()?.find { it.uri.toString() == u }
    private fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_SHORT).show()

    // ─── 배치 임베드 ───
    fun embedBatch() {
        val a = sel ?: return
        val items = a.selected.toList().mapNotNull { u ->
            val name = vidOf(u)?.name ?: return@mapNotNull null
            val code = Regex("([A-Za-z]{2,7}-\\d{2,5})").find(name)?.value?.uppercase() ?: return@mapNotNull null
            Uri.parse(u) to code
        }
        if (items.isEmpty()) { toast("선택 없음 / 품번 추출 실패"); return }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val ll = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val tv = TextView(act)
        val pbAll = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = items.size }
        val lblOne = TextView(act).apply { textSize = 12f; setPadding(0, 12, 0, 0) }
        val pbOne = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val btnCancel = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "중단" }
        ll.addView(tv); ll.addView(pbAll); ll.addView(lblOne); ll.addView(pbOne); ll.addView(btnCancel)
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle("임베드 중 (${items.size}개)").setView(ll).setCancelable(false).create()
        btnCancel.setOnClickListener {
            cancelled.set(true); btnCancel.isEnabled = false; btnCancel.text = "중단 중… (현재 작품 완료 후)"
        }
        dlg.show()
        JEmbed.embedBatch(act, items,
            onProgress = { idx, total, code, stage, pct ->
                tv.text = "전체 ${idx + 1}/$total"
                pbAll.progress = idx
                lblOne.text = "$code   $stage   $pct%"
                pbOne.progress = if (stage == "remux") pct else if (stage == "embed") 100 else 0
            },
            onDone = { ok, fail, fails ->
                dlg.dismiss(); exit(); onReload()
                val head = if (cancelled.get()) "중단됨" else "완료"
                val msg = "$head: 성공 $ok, 실패 $fail" +
                    if (fails.isNotEmpty()) "\n" + fails.take(3).joinToString("\n") else ""
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
            },
            cancel = { cancelled.get() })
    }

    // ─── 배치 이동 ───
    fun moveBatch() {
        val a = sel ?: return
        val items = a.selected.toList().mapNotNull { u -> vidOf(u)?.let { Uri.parse(u) to it.name } }
        if (items.isEmpty()) { toast("선택 없음"); return }
        pickFolder(
            onInternal = { dir -> runMoveProgress(items) { onP, onD -> JMove.move(act, items, dir, onP, onD) } },
            onSaf = { doc -> runMoveProgress(items) { onP, onD -> JMove.moveToSaf(act, items, doc, onP, onD) } }
        )
    }

    private fun runMoveProgress(
        items: List<Pair<Uri, String>>,
        mover: ((Int, Int, String) -> Unit, (Int, Int, List<String>) -> Unit) -> Unit
    ) {
        val ll = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val tv = TextView(act)
        val pb = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = items.size }
        ll.addView(tv); ll.addView(pb)
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle("이동 중 (${items.size}개)").setView(ll).setCancelable(false).create()
        dlg.show()
        mover({ idx, _, name -> tv.text = "${idx + 1}/${items.size}   $name"; pb.progress = idx },
            { ok, fail, fails ->
                dlg.dismiss(); exit(); onReload()
                val msg = "이동 완료: 성공 $ok, 실패 $fail" +
                    if (fails.isNotEmpty()) "\n" + fails.take(3).joinToString("\n") else ""
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
            })
    }

    // 반화면 폴더트리(BottomSheet) — 내부저장소(File) + 외부저장소(SAF 트리) 탐색 후 '여기로 이동'.
    private fun pickFolder(
        onInternal: (String) -> Unit,
        onSaf: (DocumentFile) -> Unit
    ) {
        val sheet = BottomSheetDialog(act)
        val outer = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val pathTv = TextView(act).apply { setPadding(8, 8, 8, 16); textSize = 13f }
        val scroll = ScrollView(act)
        val listLl = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listLl)
        val moveBtn = MaterialButton(act).apply { text = "여기로 이동" }
        var mode = 0
        var curFile: File? = null
        var curDoc: DocumentFile? = null
        fun addRow(text: String, onClick: () -> Unit) {
            listLl.addView(TextView(act).apply {
                this.text = text; textSize = 15f; setPadding(8, 28, 8, 28); setOnClickListener { onClick() }
            })
        }
        fun render() {
            listLl.removeAllViews()
            when (mode) {
                0 -> {
                    pathTv.text = "대상 저장소 선택"; moveBtn.visibility = View.GONE
                    addRow("📁  내부저장소") { mode = 1; curFile = Environment.getExternalStorageDirectory(); render() }
                    for (t in SafTrees.all(act)) {
                        val doc = DocumentFile.fromTreeUri(act, Uri.parse(t))
                        if (doc != null) addRow("💾  ${doc.name ?: "외부저장소"}") { mode = 2; curDoc = doc; render() }
                    }
                }
                1 -> {
                    val cur = curFile!!; pathTv.text = cur.absolutePath; moveBtn.visibility = View.VISIBLE
                    addRow("⬆  ..") { val p = cur.parentFile; if (p != null) curFile = p else mode = 0; render() }
                    cur.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { d ->
                        addRow("📁  ${d.name}") { curFile = d; render() }
                    }
                }
                2 -> {
                    val cur = curDoc!!; pathTv.text = cur.name ?: "외부저장소"; moveBtn.visibility = View.VISIBLE
                    addRow("⬆  ..") { val p = cur.parentFile; if (p != null) curDoc = p else mode = 0; render() }
                    cur.listFiles().filter { it.isDirectory }.sortedBy { (it.name ?: "").lowercase() }.forEach { d ->
                        addRow("📁  ${d.name}") { curDoc = d; render() }
                    }
                }
            }
        }
        moveBtn.setOnClickListener {
            sheet.dismiss()
            when (mode) { 1 -> onInternal(curFile!!.absolutePath); 2 -> onSaf(curDoc!!) }
        }
        outer.addView(pathTv)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(moveBtn)
        render(); sheet.setContentView(outer); sheet.show()
    }
}
