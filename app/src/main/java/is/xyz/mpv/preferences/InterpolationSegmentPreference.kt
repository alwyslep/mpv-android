package `is`.xyz.mpv.preferences

import android.content.Context
import android.util.AttributeSet
import `is`.xyz.mpv.R

/**
 * 22: 보간(interpolation)을 끄기/켜기 세그먼트 토글로. 기존 다이얼로그(스위치+sync 드롭다운)를 대체.
 * value(off/on)는 영속화하지 않고 실제 두 키(video_interpolation, video_sync)를 직접 읽고/쓴다.
 */
class InterpolationSegmentPreference(context: Context, attrs: AttributeSet?) :
    SegmentListPreference(context, attrs) {
    init {
        isPersistent = false
    }

    override fun getValue(): String =
        if (sharedPreferences?.getBoolean("video_interpolation", false) == true) "on" else "off"

    override fun setValue(value: String?) {
        super.setValue(value)
        val on = value == "on"
        sharedPreferences?.edit()
            ?.putBoolean("video_interpolation", on)
            ?.putString(
                "video_sync",
                if (on) "display-resample"
                else resources.getString(R.string.pref_video_interpolation_sync_default)
            )
            ?.apply()
    }
}
