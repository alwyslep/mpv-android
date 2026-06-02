package `is`.xyz.mpv

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * 유저스크립트(.lua/.js) 관리 — mpv 는 config-dir(/sdcard/mpv)/scripts/*.lua,*.js 를 자동 로드.
 * 목록 + 추가(SAF import→복사) + 켜기/끄기(.off rename, mpv 무시) + 삭제. (mpv 재시작/재생 시 적용)
 * .conf 편집은 별도(설정>고급 ConfigEditDialogPreference).
 */
class ScriptsActivity : AppCompatActivity() {
    private val dir: File by lazy { File(Utils.mpvConfigDir(), "scripts").also { it.mkdirs() } }
    private lateinit var listLl: LinearLayout

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importScript(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuroraDrawable.apply(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "스크립트 (.lua/.js)"
            navigationIcon = getDrawable(android.R.drawable.ic_menu_close_clear_cancel)
            setNavigationOnClickListener { finish() }
        }
        toolbar.menu.add(0, 1, 0, "추가").apply {
            setIcon(android.R.drawable.ic_input_add); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener { pick.launch(arrayOf("*/*")); true }
        val scroll = ScrollView(this)
        listLl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 32) }
        scroll.addView(listLl)
        root.addView(toolbar)
        root.addView(scroll)
        setContentView(root)
        render()
    }

    private fun render() {
        listLl.removeAllViews()
        val files = dir.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".lua") || it.name.endsWith(".js") ||
                it.name.endsWith(".lua.off") || it.name.endsWith(".js.off"))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (files.isEmpty()) {
            listLl.addView(TextView(this).apply {
                text = "스크립트 없음 — 우상단 + 로 .lua/.js 추가\n${dir.path}"
                setPadding(8, 32, 8, 8)
            })
            return
        }
        for (f in files) {
            val active = !f.name.endsWith(".off")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }
            val sw = SwitchCompat(this).apply { isChecked = active; setOnCheckedChangeListener { _, c -> toggle(f, c) } }
            val tv = TextView(this).apply {
                text = f.name.removeSuffix(".off"); textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(16, 0, 16, 0)
            }
            val del = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "삭제"; setOnClickListener { f.delete(); render() }
            }
            row.addView(sw); row.addView(tv); row.addView(del)
            listLl.addView(row)
        }
    }

    private fun toggle(f: File, active: Boolean) {
        val target = if (active) File(dir, f.name.removeSuffix(".off")) else File(dir, f.name + ".off")
        if (f.name != target.name) f.renameTo(target)
        render()
    }

    private fun importScript(uri: Uri) {
        val name = displayName(uri)
        if (!(name.endsWith(".lua") || name.endsWith(".js"))) {
            Toast.makeText(this, ".lua / .js 파일만 가능", Toast.LENGTH_SHORT).show(); return
        }
        try {
            File(dir, name).outputStream().use { out -> contentResolver.openInputStream(uri)?.use { it.copyTo(out) } }
            Toast.makeText(this, "추가: $name (mpv 재생/재시작 후 적용)", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) { val n = it.getString(0); if (!n.isNullOrBlank()) return n }
            }
        } catch (_: Throwable) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "script.lua"
    }
}
