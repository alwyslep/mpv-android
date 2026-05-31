package `is`.xyz.mpv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

// P2: 폴더 진입 → 해당 폴더의 비디오 목록. 탭 시 곧장 MPVActivity 재생.
class FolderVideosActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)

        val path = intent.getStringExtra("path") ?: ""
        val name = intent.getStringExtra("name") ?: "폴더"

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        Thread {
            val vids = MediaLibrary.videosIn(MediaLibrary.queryVideos(this), path)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                recycler.adapter = VideoAdapter(vids) { v -> play(v) }
            }
        }.start()
    }

    private fun play(v: Vid) {
        Recents.add(this, v.uri.toString(), v.name)
        val i = Intent(Intent.ACTION_VIEW, v.uri)
        i.setClass(this, MPVActivity::class.java)
        startActivity(i)
    }
}
