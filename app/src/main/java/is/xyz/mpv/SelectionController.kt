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

/**
 * jembed/이동 선택모드 공통 컨트롤러 — MediaLibraryActivity·FolderVideosActivity 가 공유.
 * 화면이 어댑터를 만들 때 bind(), sel_bar 버튼을 enter/embedBatch/moveBatch/exit 에 연결.
 * 품번/이름은 adapter.items(Vid)에서 직접 추출 — 각 화면의 별도 목록 의존 제거.
 */
class SelectionController(
    private val act: AppCompatActivity,
    private val selBar: View,
    private val selCount: TextView,
    private val onReload: () -> Unit
) {
    var adapter: VideoAdapter? = null
        private set

    fun bind(a: VideoAdapter) { adapter = a; a.onSelectionChanged = { update() } }
    fun unbind() { adapter = null }

    val isActive: Boolean get() = adapter?.selectionMode == true

    fun enter() {
        val a = adapter
        if (a == null) { toast("'영상' 보기 모드 또는 폴더 안에서 선택하세요"); return }
        a.selectionMode = true; a.notifyDataSetChanged()
        selBar.visibility = View.VISIBLE; update()
    }

    fun exit() {
        adapter?.let { it.selectionMode = false; it.selected.clear(); it.notifyDataSetChanged() }
        selBar.visibility = View.GONE
    }

    fun update() { selCount.text = "${adapter?.selected?.size ?: 0}개 선택" }

    private fun vidOf(u: String): Vid? = adapter?.items?.find { it.uri.toString() == u }
    private fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_SHORT).show()

    // ─── 배치 임베드 ───
    fun embedBatch() {
        val a = adapter ?: return
        val items = a.selected.toList().mapNotNull { u ->
            val name = vidOf(u)?.name ?: return@mapNotNull null
            val code = Regex("([A-Za-z]{2,7}-\\d{2,5})").find(name)?.value?.uppercase() ?: return@mapNotNull null
            Uri.parse(u) to code
        }
        if (items.isEmpty()) { toast("선택 없음 / 품번 추출 실패"); return }
        val ll = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val tv = TextView(act)
        val pb = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        ll.addView(tv); ll.addView(pb)
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle("임베드 중 (${items.size}개)").setView(ll).setCancelable(false).create()
        dlg.show()
        JEmbed.embedBatch(act, items,
            onProgress = { idx, total, code, stage, pct ->
                tv.text = "${idx + 1}/$total   $code   $stage   $pct%"
                pb.progress = if (stage == "remux") pct else if (stage == "embed") 100 else 0
            },
            onDone = { ok, fail, fails ->
                dlg.dismiss(); exit(); onReload()
                val msg = "완료: 성공 $ok, 실패 $fail" +
                    if (fails.isNotEmpty()) "\n" + fails.take(3).joinToString("\n") else ""
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
            })
    }

    // ─── 배치 이동 ───
    fun moveBatch() {
        val a = adapter ?: return
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
