package `is`.xyz.mpv

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * 3: 라이브러리 데이터 백업/복원/병합 — 재생위치·트랙·평점·찜·장면지정(품번/파일명 키).
 * SAF 로 단일 JSON 파일 내보내기/가져오기. 썸네일 이미지 캐시는 재생성 가능하므로 제외(데이터만).
 */
class DataBackupActivity : AppCompatActivity() {
    private var mergeMode = false

    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(CacheBackup.export(this).toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        toast(if (ok) "백업 완료" else "백업 실패")
    }

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val json = runCatching { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
        val ok = json != null && CacheBackup.import(this, json, mergeMode)
        toast(if (ok) (if (mergeMode) "병합 완료" else "복원 완료") + " — 앱 다시 들어가면 반영" else "가져오기 실패")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuroraDrawable.apply(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "데이터 백업 · 복원"
            navigationIcon = getDrawable(android.R.drawable.ic_menu_close_clear_cancel)
            setNavigationOnClickListener { finish() }
        }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 32); gravity = Gravity.CENTER_HORIZONTAL }
        ll.addView(TextView(this).apply {
            text = "재생위치 · 트랙 · 평점 · 즐겨찾기 · 장면 썸네일 지정\n(품번/파일명 기준 — 기기·폴더 이동 무관)"
            setPadding(0, 0, 0, 28); textSize = 13f
        })
        fun btn(label: String, onClick: () -> Unit) = ll.addView(MaterialButton(this).apply {
            text = label; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 12 }
        })
        btn("백업 (파일로 내보내기)") { exporter.launch("mpv-aurora-data.json") }
        btn("복원 (덮어쓰기)") { mergeMode = false; importer.launch(arrayOf("application/json", "*/*")) }
        btn("병합 (기존과 합치기)") { mergeMode = true; importer.launch(arrayOf("application/json", "*/*")) }
        root.addView(toolbar); root.addView(ll)
        setContentView(root)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
