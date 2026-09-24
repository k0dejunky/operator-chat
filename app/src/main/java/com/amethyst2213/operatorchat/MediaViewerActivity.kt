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
            val bmp = if (file.isFile) decodeSampled(file) else null
            runOnUiThread {
                if (bmp != null) image.setImageBitmap(bmp) else finish()
            }
        }.start()

        WindowInsetsCompat.toWindowInsetsCompat(window.decorView.rootWindowInsets)
    }

    /**
     * Decode a large image with a power-of-two inSampleSize so full-resolution
     * photos cannot OOM the app. The sample size is chosen to keep the decoded
     * bitmap near screen resolution rather than the raw file's dimensions.
     */
    private fun decodeSampled(file: File): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            val target = 1920
            while (bounds.outWidth / (sample * 2) >= target &&
                bounds.outHeight / (sample * 2) >= target
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                },
            )
        } catch (_: Exception) {
            null
        }
    }
}