package `is`.xyz.mpv.preferences

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import `is`.xyz.mpv.BuildConfig
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvLogLevel
import `is`.xyz.mpv.R
import `is`.xyz.mpv.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity(), MPVLib.LogObserver {
    private lateinit var binding: ActivityAboutBinding
    private var logs = ""
    private var mpvDestroyed = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (preferences.getBoolean("material_you_theming", false))
            DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.elevation = 0f
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        logs = "mpv-android ${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TYPE})\n"

        // create mpv context to capture version info from log
        MPVLib.create(this)
        mpvDestroyed = false
        MPVLib.addLogObserver(this)
        MPVLib.init()
    }

    private fun updateLog() {
        runOnUiThread {
            // mpv 로그(prettify) 먼저, 변경이력은 맨 아래. NestedScrollView 로 세로 스크롤.
            binding.logs.text = prettify(logs) + "\n\n" + CHANGELOG
        }
    }

    // "Configuration:" / "List of enabled features:" 줄(공백으로 길게 한 줄)을 항목마다 줄바꿈해 세로 목록으로.
    private fun prettify(s: String): String =
        s.split("\n").joinToString("\n") { line ->
            when {
                line.startsWith("Configuration:") -> wrapTokens("Configuration:", line)
                line.startsWith("List of enabled features:") -> wrapTokens("List of enabled features:", line)
                else -> line
            }
        }

    private fun wrapTokens(prefix: String, line: String): String =
        prefix + "\n" + line.removePrefix(prefix).trim()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("\n") { "  $it" }

    companion object {
        // mpv-aurora(JAV 라이브러리 전용 빌드) 변경이력 — mpv 정보 화면 맨 아래. 최신이 위.
        private val CHANGELOG = """
            ════════════════════════════════
             mpv-aurora · JAV 라이브러리 전용 빌드
             변경이력 (최신순)
            ════════════════════════════════

            [v30] 필터 시트 UX
             · 적용/초기화 버튼을 스크롤 밖 하단 고정
             · 시트 펼침(EXPANDED)으로 항상 노출
             · 적용 시 '필터 적용됨' 토스트

            [v29] conf·스크립트 통합 관리 (설정>고급)
             · .conf(config-dir 루트)+.lua/.js(scripts/) 한 화면
             · 추가 = URL(GitHub raw)/파일(SAF)/직접입력
             · 항목별 편집(monospace)·토글·삭제
             · GitHub 동기화(mpv-config/manifest.json pull)
             · 구버전 conf 편집(filesDir, mpv 미반영) 버그 제거

            [v28] 커버 정렬 (빠른설정)
             · 좌/중앙/우 — 임베드 커버만 MATRIX crop
             · 추출 썸네일은 중앙(centerCrop) 고정

            [v27] 배치 임베드 안정화
             · graceful 중단(현재 작품 완료 후 멈춤)
             · 전체 진행바 + 개별 단계% 동시 표시

            [v26] 메타 필드 필터
             · 배우·스튜디오·시리즈·장르 다중선택
             · hub /meta-index 1회 캐시, 품번=파일명 추출

            [v25] 필터 워크플로
             · 전체선택 토글 버튼(sel_bar)
             · 임베드없음 필터를 folder/tree 전화면 적용

            [v24] 설정·스크립트 APK 번들
             · assets/mpv-config 시드(없을 때만)
             · /sdcard/mpv 삭제해도 기본 세트 자동 복원

            [v23] 트리 모드 선택
             · TreeActivity 에도 선택(임베드/이동)

            [v22] 폴더 진입 선택
             · FolderVideosActivity 선택바·메뉴 추가
             · 홈은 영상 평면 모드만 선택 메뉴 노출

            [v20] 해상도 불일치 마커
             · 실제 height(MediaStore) vs hub 최고화질
             · 저화질/부분 다운 시 '720p→1080p ⚠'

            [v18] jembed 배치 + 임베드없음 필터
             · 선택 다중 임베드, 진행 다이얼로그

            [v15] jembed 엔진 + 외부저장소
             · 메타 직접 atom patch(moov 이동)
             · TS→mp4 remux(MediaExtractor/Muxer)
             · USB/SD SAF 통합(Os.pread/pwrite)

            [v6] 기반
             · 동적 오로라 배경(12효과×속도)
             · 커버 크기 슬라이더(빠른설정)
             · 길이 불일치 마커 + 미디어 라이브러리 홈
            ════════════════════════════════
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!mpvDestroyed) {
            MPVLib.destroy()
            mpvDestroyed = true
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (prefix != "cplayer")
            return
        if (level == MpvLogLevel.MPV_LOG_LEVEL_V)
            logs += text

        if (text.startsWith("List of enabled features:", true)) {
            // stop receiving log messages and populate text field
            MPVLib.removeLogObserver(this)
            updateLog()
        }
    }
}
