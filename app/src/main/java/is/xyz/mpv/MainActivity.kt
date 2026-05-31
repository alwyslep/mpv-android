package `is`.xyz.mpv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// P1 (launcher): 타일 화면(MainScreenFragment) 대신 파일 브라우저(FilePickerActivity)를
//   "홈"으로 바로 띄운다. NextPlayer 흐름(시동→폴더목록→탭→재생) 재현의 진입점.
//   MainActivity 는 런처 아이콘 진입 셸일 뿐이므로 즉시 FilePicker HOME 으로 넘기고 종료한다.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val i = Intent(this, FilePickerActivity::class.java)
        i.putExtra("skip", FilePickerActivity.HOME)
        startActivity(i)
        finish()
    }
}
