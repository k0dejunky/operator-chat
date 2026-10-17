package com.amethyst2213.operatorchat

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Full-screen in-app attachment viewer.
 *
 *  - Images: sampled decode on a worker thread (a full-res photo cannot OOM
 *    the app), tap to dismiss.
 *  - Videos: ExoPlayer (media3). The server stores attachments without an
 *    extension (`full_<id>_<ts>.img`), so the container is detected from the
 *    magic bytes via [MediaSniff] and pinned onto the MediaItem; an unplayable
 *    item just closes the viewer instead of showing a black screen.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaViewerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val file = File(path)
        if (!file.isFile) {
            finish()
            return
        }

        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val pv = PlayerView(this).apply {
            visibility = View.GONE
            useController = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setShutterBackgroundColor(0xFF000000.toInt())
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            // The in-view fullscreen button re-launches into an immersive mode
            // this single-purpose activity does not need; hide it.
            findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)?.visibility = View.GONE
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(image)
            addView(pv)
        }
        setContentView(root)
        image.setOnClickListener { finish() }

        val videoMime = MediaSniff.videoHint(file)
        if (videoMime != null) {
            playVideo(file, videoMime, image, pv)
        } else {
            Thread {
                val bmp = decodeSampled(file)
                runOnUiThread {
                    if (bmp != null) {
                        image.setImageBitmap(bmp)
                    } else {
                        // Not a decodable image — last resort: let ExoPlayer
                        // try (audio files play too, an unsupported file just
                        // closes the viewer via onPlayerError).
                        playVideo(file, null, image, pv)
                    }
                }
            }.start()
        }
    }

    private fun playVideo(file: File, mime: String?, image: ImageView, pv: PlayerView) {
        image.visibility = View.GONE
        pv.visibility = View.VISIBLE

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView = pv
        pv.player = exo
        pv.setOnClickListener { finish() }

        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .apply { if (mime != null) setMimeType(mime) }
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                finish()
            }
        })
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        playerView?.player = null
        player?.release()
        player = null
        playerView = null
        super.onDestroy()
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
