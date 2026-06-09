package `is`.xyz.mpv

// B-53: 품번(품번) 인식 공용 파서 — Thumbs.MediaKey / MediaLibrary / SelectionController /
//   VideoDetailActivity 4곳 통일. 상세 패턴군 SSOT: scripts-work/docs/code_patterns.md.
//
// ⚠️ 반환값은 hub 키(queue.sqlite `code` 컬럼)와 일치해야 함(duration/resolution/meta 조회).
//   따라서 정규화(FC2-PPV→FC2, 0패딩 제거 등) 금지 — **raw 매치를 대문자로만** 반환.
//   실측 저장형태: FC2 `FC2-PPV-1001883`(PPV 유지), 날짜형 `010321-411`·`010122_582`(원본 구분자),
//   Tokyo-Hot `N0615`·`K1252`. 표준 `SW-171`.
//
// 매칭 순서 = 저오탐·고가치 특화 먼저, 표준 라벨-숫자, 마지막 좁은 무하이픈 접두.
//   (무하이픈 일반형 IT0001/GEDO0078 등은 영단어 파일명 오탐 위험 → 보류, docs TODO.)
object JavCode {
    // 해상도/화질 꼬리표(코드 아님) 제거 — 'hd' 는 라벨과 모호해 제외.
    private val RESOLUTION = Regex("(?i)\\b(\\d{3,4}p|2160p?|[248]k|fhd|uhd)\\b")
    // ① 날짜형 무수정: carib(-)/1pon·paco·mura(_)/10mu. 6자리 + 구분자 + 2~3자리.
    private val DATE = Regex("\\d{6}[-_]\\d{2,3}")
    // ② 표준 라벨-숫자 + 2세그먼트 접두(FC2-PPV·XXX-AV). 현행 정규식 유지(하위호환).
    private val LABEL = Regex("(?:[A-Za-z0-9]{2,4}-)?[A-Za-z]{2,7}-\\d{1,8}")
    // ③ Tokyo-Hot 류 좁은 무하이픈 접두(단일/짧은 + 숫자). 화이트리스트라 오탐 제한.
    private val UNCEN_PREFIX = Regex("(?i)\\b(?:gedo|red|cz|se|n|k)\\d{2,5}\\b")

    /** 파일명/제목에서 품번 추출 → 대문자 raw 코드, 못 찾으면 null. */
    fun extract(name: String): String? {
        val s = RESOLUTION.replace(name.substringBeforeLast('.'), " ")
        DATE.find(s)?.let { return it.value.uppercase() }
        LABEL.find(s)?.let { return it.value.uppercase() }
        UNCEN_PREFIX.find(s)?.let { return it.value.uppercase() }
        return null
    }

    // 무하이픈/무구분 라벨숫자 폴백(MIDV189, ABP410) — 중복 그룹핑 전용.
    private val LOOSE = Regex("(?i)\\b([a-z]{2,6})[ _]?(\\d{3,5})\\b")

    /**
     * 중복 그룹핑 전용 키 — hub 키 아님(조회용 아님).
     *  extract() 가 변형 접미사(-C 중국자막·_2/_part·무수정·(1) 등)는 코드만 뽑아 이미 흡수.
     *  추가로 구분자 무관 정규화 + 무하이픈 형(MIDV189) 폴백으로 하이픈본과 합류.
     *  무하이픈 폴백 오탐(영단어)은 단독이면 그룹에서 탈락하므로 검수 화면 영향 제한적.
     */
    fun dupKey(name: String): String? {
        extract(name)?.let { return norm(it) }
        val base = name.substringBeforeLast('.')
        // 무하이픈 폴백은 '파일명이 곧 코드'(공백 없는 짧은 형)일 때만.
        //  서술형 제목(예: "Korea1818 Com - Hot Threesome…") 속 스튜디오명/숫자 오탐 차단 — 공백 있으면 거름.
        if (base.contains(' ')) return null
        val s = RESOLUTION.replace(base, " ")
        LOOSE.find(s)?.let { return (it.groupValues[1] + it.groupValues[2]).uppercase() }
        return null
    }

    private fun norm(c: String) = c.uppercase().replace("-", "").replace("_", "").replace(" ", "")
}
