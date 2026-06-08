package `is`.xyz.mpv

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup

/**
 * B-57(UI): 오로라 그라데이션 앰비언트 배경을 *모든 액티비티*에 전역 적용.
 *
 * ActivityLifecycleCallbacks 로 생성되는 모든 액티비티(플레이어 MPVActivity·라이브러리·폴더·
 * 상세·검색·트리·브라우즈·파일선택·설정·소개 등)의 window 배경을 [AuroraDrawable] 로 바꾸고,
 * content 루트 뷰를 투명화해 그 오로라가 비치게 한다. 영상 surface 등 불투명하게 칠하는 곳은
 * 그대로 덮으므로(영상 제외) "전체 적용 + 영상만 제외 + 검은 바탕엔 적용"이 한 곳에서 충족된다.
 */
class MpvApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // onActivityCreated 는 액티비티 onCreate(setContentView 포함) 직후 호출됨.
                AuroraDrawable.apply(activity)
                try {
                    val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    content?.getChildAt(0)?.setBackgroundColor(Color.TRANSPARENT)
                } catch (_: Throwable) {
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) { JobBanner.attach(activity) }   // B-68 진행 배너(지속)
            override fun onActivityPaused(activity: Activity) { JobBanner.detach(activity) }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
