package com.amethyst2213.operatorchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Client-side image preparation before an attachment is uploaded:
 * downscale to a sane maximum edge, apply the EXIF orientation into the
 * pixels, and re-encode as JPEG (which also drops the original metadata,
 * including GPS). Videos and other types pass through untouched.
 *
 * The decision helpers are pure so they unit-test without Android; the
 * decode/encode half is exercised on-device via the attach flow.
 */
object ImagePrep {
    /** Longest edge after compression. Full-res phone photos are ~4000px. */
    const val MAX_DIMENSION = 1920

    /** JPEG quality for re-encoded photos (visually lossless at this size). */
    const val JPEG_QUALITY = 85

    /** Images below this get a pass: already small, don't re-encode them. */
    const val SKIP_UNDER_BYTES = 512L * 1024L

    fun shouldCompress(mime: String?, sizeBytes: Long): Boolean =
        mime != null && mime.startsWith("image/") && mime != "image/gif" && sizeBytes >= SKIP_UNDER_BYTES

    /**
     * Power-of-two sample size so the decoded bitmap's longest edge is >= the
     * target (BitmapFactory only supports powers of two). Mirrors the viewer's
     * inSampleSize maths.
     */
    fun sampleSizeFor(width: Int, height: Int, maxDim: Int = MAX_DIMENSION): Int {
        if (width <= 0 || height <= 0 || maxDim <= 0) return 1
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= maxDim) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /** Clockwise rotation (degrees) that bakes an EXIF orientation into pixels. */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /** True for the mirrored EXIF orientations (a flip is needed too). */
    fun isMirrored(exifOrientation: Int): Boolean = when (exifOrientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
        ExifInterface.ORIENTATION_FLIP_VERTICAL,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE -> true
        else -> false
    }

    /**
     * Compress [source] into [dest] (JPEG). Runs blocking IO — call from a
     * background dispatcher. Returns true when a compressed file was written;
     * false leaves [source] as the attachment (unsupported/corrupt image).
     */
    fun compressToJpeg(source: File, dest: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val decoded = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return false

            val orientation = try {
                ExifInterface(source.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Throwable) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val degrees = rotationDegrees(orientation)
            val mirrored = isMirrored(orientation)
            val bitmap = if (degrees == 0 && !mirrored) {
                decoded
            } else {
                val matrix = Matrix().apply {
                    if (mirrored) postScale(-1f, 1f)
                    if (degrees != 0) postRotate(degrees.toFloat())
                }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            }

            val scaled = scaleToMaxEdge(bitmap)
            val ok = try {
                FileOutputStream(dest).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            } finally {
                if (scaled !== bitmap) bitmap.recycle()
                scaled.recycle()
            }
            ok && dest.length() > 0
        } catch (_: Throwable) {
            dest.delete()
            false
        }
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxDim: Int = MAX_DIMENSION): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest.toFloat()
        val w = max(1, (bitmap.width * ratio).toInt())
        val h = max(1, (bitmap.height * ratio).toInt())
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * Copy a picked content:// image into [dest], compressing when it is a
     * large image. Returns the file that should be uploaded, or null on
     * failure. Blocking IO — background dispatcher.
     */
    fun importPicked(context: Context, uri: Uri, dest: File): File? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out, 128 * 1024) }
            } ?: return null
            dest
        } catch (_: Throwable) {
            dest.delete()
            null
        }
    }
}
