package com.amethyst2213.operatorchat

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * Full-screen in-app image viewer. The activity receives the path of a
 * downloaded attachment file and shows it edge-to-edge (tap to dismiss).
 */
class MediaViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val path = intent.getStringExtra("path") ?: run { finish(); return }

        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        setContentView(image)
        image.setOnClickListener { finish() }

        Thread {
            val file = File(path)
            val bmp = if (file.isFile) BitmapFactory.decodeFile(path) else null
            runOnUiThread {
                if (bmp != null) image.setImageBitmap(bmp) else finish()
            }
        }.start()

        WindowInsetsCompat.toWindowInsetsCompat(window.decorView.rootWindowInsets)
    }
}