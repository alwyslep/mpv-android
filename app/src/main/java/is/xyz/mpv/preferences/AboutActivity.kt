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

            [v70] 품번 인식 확장 (53)
             · 품번 파서 공용화(JavCode) — 분류·썸네일캐시·마커·임베드 4곳 통일
             · 무수정 날짜형(카리비안 010321-411 · 1pondo 010122_582) 인식 추가
             · Tokyo-Hot(N0615·K1252) 인식 추가
             · 해상도 꼬리표(1080p 등) 제거 후 매칭(오인식 감소)
             · 패턴 SSOT 문서화(docs/code_patterns.md), 중화권·무하이픈은 향후 보강

            [v69] 커스텀 툴팁 색 (55)
             · 버튼·아이콘 툴팁을 커스텀(어두운 녹색 배경 + 밝은 주황 글자)으로
             · 시스템 툴팁(A13 색 테마 불가) → PopupWindow 자체 구현
             · long-press·마우스 hover 둘 다, 툴바 아이콘 포함

            [v68] 타일 삭제(휴지통으로) (59)
             · 타일 길게눌러 메뉴에 '삭제(휴지통으로)' 추가
             · 내부저장(MediaStore): 안드로이드 네이티브 휴지통(30일 복구)
             · 외부 USB/SD(SAF): 드라이브의 .mpv-trash 폴더로 이동(복구 가능)

            [v67] 분류 재연결 + UX 보강 (51·54·57·58 + 장르/클릭)
             · 배우·장르 원자화 — 다중 배우/장르를 개별 항목으로 분해
             · 상세보기 배우/시리즈/스튜디오/장르 칩 클릭 → 그 엔티티의 모든 작품 → 바로 시청
             · 분류에 '장르' 차원 추가
             · 천단위 콤마 — 전체스캔 진행·분류/검색 카운트(1,350/11,680)
             · 우측 반투명 플로팅 스크롤바(스크롤 중만 표시, 자동숨김) — 좌측미러 롤백
             · 정렬·빠른설정·필터 아이콘 — 설정 걸리면 색(amber)
             · 라이브러리 RTL 레이아웃 토글(설정)
             ※ v67~83 미기록 공백을 changelog 기준으로 재정렬(이후 매 빌드 기록)

            [v66] 화면별 독립 정렬 (26)
             · 정렬을 화면(scope)별로 분리 — 홈폴더/홈영상/트리/폴더/외부/검색 각각 기억
             · 정렬 키 확장: 제목·날짜·크기·길이·평점 + 재생진행률·찜
             · 각 화면 상단에 정렬 버튼(SortDialog, 키+오름/내림)
             · 검색·트리도 정렬 적용(이전엔 이름순 고정)
             · 빠른설정에서 정렬 섹션 제거(화면별로 이동)

            [v65] 임베드 후 커버 즉시 반영 (27)
             · 임베드 성공 시 타일이 옛 자동썸네일 대신 새 커버 표시
             · 원인=캐시 무효화가 디스크 키(품번/파일명)와 불일치(uri 해시로 삭제)
             · invalidate 가 diskKey+장면지정 변형까지 정확히 삭제

            [v64] 설정 정리 (18·19)
             · 그리드/보기모드 중복 제거(빠른설정과 동일 → 빠른설정만)
             · '기본 파일 관리자 경로' → '파일 선택기 시작 경로'

            [v63] 언어 프리셋 (20)
             · 기본 오디오/자막을 드롭다운 프리셋으로(코드 타이핑 제거)
             · 오디오 일/영/한/중/자동(기본 jpn), 자막 한/영/일/한→영/자동(기본 kor)

            [v62] 화질 필터 인라인 프리셋 (22)
             · 업/다운스케일·시간보간을 큐레이션 프리셋 드롭다운(38종 raw 대신)
             · 디밴딩·보간을 세그먼트 토글 — 팝업 다이얼로그 전부 제거

            [v61] 백그라운드 재생 토글 (21)
             · 팝업 → 인라인 3버튼 세그먼트(안 함/오디오/항상)

            [v60] 위험 액션 3중 안전장치 (17)
             · 인덱스 재생성/등록폴더 초기화/썸네일캐시 비우기 — 키워드 확인

            [v59] 더보기 팝업 다크 + 아이콘 안내 (24·25)
             · 재생 중 더보기 팝업 다크 테마 안정화
             · 우상단 잠금/PiP 아이콘 기능 토스트 안내

            [v57] 드라이브간 이동 안정화 (14)
             · 이동 끝난 파일 썸네일 즉시 제거(Uri equals 비교 수정)
             · 이동 중단(우아한 종료) — 진행 중 작품은 끝까지

            [v56] 개별 장면 썸네일 (정밀 지점)
             · 재생 중 현재 장면을 그 영상 썸네일로(더보기 메뉴)
             · 일괄 %슬라이더 지정과 공존

            [v54~55] 장면 썸네일 커버 판정 수정
             · 커버 '이미지'(embeddedPicture) 유무로 제외 — 메타만 있고 커버 없는 파일도 적용

            [v53] 데이터 백업/복원/병합 (3)
             · 재생위치·트랙·평점·찜·장면지정 → JSON(품번 키)
             · 기기·재설치·이동 무관 복원(설정>고급 '데이터 백업·복원')

            [v52] 라이브러리 데이터 품번 키 (1-2차)
             · 재생위치/오디오·자막 트랙/평점/찜도 품번·파일명(MediaKey) 기준
             · 파일 이동·임베딩·재다운로드해도 유지

            [v51] 장면 썸네일 일괄 루틴
             · 선택모드 배치(선택/필터/이동에 '썸네일' 추가)
             · % 위치 슬라이더 일괄 지정 + 해제, 커버 있는 파일 제외

            [v50] 썸네일 캐시 품번 키 (1-1차)
             · 썸네일·장면지정 캐시 키를 품번→파일명→uri 로(경로/이동 무관)

            [v49] 장면 썸네일 커버파일 적용 (2)
             · 캐시 키에 장면 지정 위치 포함 → 커버 있는 파일도 분리 적용

            [v47~48] 재생 제스처·제목 수정 (4·5·6)
             · 컨트롤 표시 중 seek/볼륨 제스처 먹통·떨림 제거(MOVE showControls 폭주)
             · 하단 컨트롤 숨김이어도 미디어 제목 표시

            [v40~46] 0~7번 개선 묶음
             · 스크롤 위치 보존(재생 복귀·목록 갱신)
             · 하단 컨트롤 표시 토글을 위치이동→표시/숨김 의미로
             · 이동 대상 폴더 우측 사이드 패널 + 마지막 위치 기억
             · 외부 앱 실행 재생을 앱 내부와 통일(이어보기/진행저장)
             · 시청 중 현재 프레임을 썸네일로 지정
             · 상세보기 커버 크기 조절(슬라이더+휠)
             · 소울 벤치마킹 — 설정 카드형 항목(오로라 비침)

            [v36~39] 오로라 배경 고도화
             · 배경 fps 22→30(더 부드럽게)
             · 채도·명도·글로우 슬라이더 + 설정 라이브 미리보기
             · 효과 3종 추가(성운·보케·빔) → 15종(프리셋 75)

            [v35] 데이터 유지 검증 빌드

            [v34] 서명 고정 + 즉시 반영
             · debug 서명 고정 → 업데이트 설치(설정·캐시 유지)
             · 임베드/이동 항목별 즉시 반영(타일 갱신/제거)
             · 진행창 비모달(배경 목록 스크롤 가능)

            [v33] 이동 속도
             · 같은 드라이브 SAF moveDocument(복사 없이 즉시)

            [v32] 메뉴 분리
             · 선택(임베드) / 필터 / 이동 독립 메뉴

            [v31] mpv 정보 화면
             · 변경이력 맨 아래·상세 + features 줄바꿈

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
             · jembed PoC v1~v4(jaudiotagger→mp4parser→직접 atom patch)

            [v6~14] 기반 구축
             · 동적 오로라 배경(12효과×속도) + 전역 적용
             · 미디어 라이브러리 홈(폴더/영상 타일, 임베드 커버·품번/제목 라벨)
             · 자체 SAF 타일 브라우저(폴더 열기)
             · 통합 검색(MediaStore+SAF 트리), 트리 모드(실제 디렉터리 계층)
             · 재생연동(이어보기·진행률 바·자동 다음재생·트랙 기억)
             · JAV 메타 상세화면(배우/시리즈/스튜디오/장르/줄거리 atom 파싱)
             · 즐겨찾기(찜)·평점(별), 시청상태 필터, 배우/스튜디오/시리즈별 뷰
             · 길이 불일치 마커(hub /durations), 커버 크기 슬라이더
             · 썸네일 디스크 캐시 + 메타 영속, B-56 review 허브 동기화
             · 전체 EN/KO 완역(설정·레이아웃·플레이어 공통문자열)

            [P1~P2] 런처 시작
             · FilePicker → NextPlayer식 홈 + SpeedDial FAB
             · MediaStore 미디어 라이브러리, 임베드 커버 우선 썸네일
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
