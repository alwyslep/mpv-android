package `is`.xyz.mpv

/**
 * 동적 배경 *프리셋* — 코어 효과 12종([AuroraEffects]) × 속도/맥동 변형 5단계 = 60종.
 *
 * 사용자는 설정의 단일 ListPreference 에서 "메시 · 은은" 처럼 분위기를 한 방에 고른다.
 * 추가로 속도 배율([AuroraDrawable.KEY_SPEED])·맥동 깊이([AuroraDrawable.KEY_PULSE_DEPTH])
 * 슬라이더로 미세조정. ListPreference 의 entries/values 는 [labels]/[ids] 로 코드에서 주입
 * (arrays.xml 중복 없이 이 파일이 SSOT).
 */
object AuroraPresets {

    data class P(
        val id: String,
        val labelEn: String,
        val labelKo: String,
        val effect: String,
        val hueSec: Int,
        val pulseSec: Int,
        val depth: Int,
        val sat: Float,
        val value: Float,
    )

    private data class Eff(
        val id: String, val ko: String, val en: String, val sat: Float, val value: Float,
    )

    private data class Spd(
        val sfx: String, val ko: String, val en: String,
        val hueSec: Int, val pulseSec: Int, val depth: Int,
    )

    private val EFFECTS = listOf(
        Eff("mesh", "메시", "Mesh", 0.62f, 0.98f),
        Eff("wave", "웨이브", "Wave", 0.66f, 0.97f),
        Eff("spiral", "나선", "Spiral", 0.70f, 0.98f),
        Eff("ripple", "물결", "Ripple", 0.68f, 0.96f),
        Eff("orbit", "궤도", "Orbit", 0.64f, 0.98f),
        Eff("curtain", "커튼", "Curtain", 0.72f, 0.97f),
        Eff("blobs", "블롭", "Blobs", 0.60f, 0.98f),
        Eff("sweep", "스윕", "Sweep", 0.74f, 0.98f),
        Eff("starfield", "별가루", "Starfield", 0.55f, 1.0f),
        Eff("kaleidoscope", "만화경", "Kaleidoscope", 0.72f, 0.98f),
        Eff("gradientflow", "흐름", "Gradient Flow", 0.66f, 0.97f),
        Eff("plasma", "플라스마", "Plasma", 0.70f, 0.96f),
        Eff("nebula", "성운", "Nebula", 0.60f, 0.95f),
        Eff("bokeh", "보케", "Bokeh", 0.65f, 1.0f),
        Eff("beam", "빔", "Beam", 0.72f, 0.98f),
    )

    private val SPEEDS = listOf(
        Spd("calm", "느긋", "Calm", 90, 8, 30),
        Spd("soft", "은은", "Soft", 45, 6, 45),
        Spd("lively", "경쾌", "Lively", 24, 5, 55),
        Spd("fast", "빠름", "Fast", 12, 4, 65),
        Spd("breathe", "호흡", "Breathe", 30, 3, 85),
    )

    val ALL: List<P> = buildList {
        for (e in EFFECTS) {
            for (sp in SPEEDS) {
                add(
                    P(
                        id = "${e.id}_${sp.sfx}",
                        labelEn = "${e.en} · ${sp.en}",
                        labelKo = "${e.ko} · ${sp.ko}",
                        effect = e.id,
                        hueSec = sp.hueSec,
                        pulseSec = sp.pulseSec,
                        depth = sp.depth,
                        sat = e.sat,
                        value = e.value,
                    )
                )
            }
        }
    }

    const val DEFAULT_ID = "mesh_soft"

    private val map: Map<String, P> = ALL.associateBy { it.id }

    fun byId(id: String?): P = map[id] ?: map[DEFAULT_ID] ?: ALL.first()

    /** ListPreference.entryValues 용 — 안정적 id. */
    fun ids(): Array<CharSequence> = ALL.map { it.id as CharSequence }.toTypedArray()

    /** ListPreference.entries 용 — ko=true 면 한글 라벨. */
    fun labels(ko: Boolean): Array<CharSequence> =
        ALL.map { (if (ko) it.labelKo else it.labelEn) as CharSequence }.toTypedArray()
}
