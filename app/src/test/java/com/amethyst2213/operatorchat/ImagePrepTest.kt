package com.amethyst2213.operatorchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of [ImagePrep]: the rules that decide whether a picked file
 * is re-encoded, how far a photo is downsampled, and how EXIF orientation is
 * baked into pixels. (The decode/encode half needs Android bitmaps and is
 * exercised on-device via the attach flow.)
 */
class ImagePrepTest {

    private val halfMb = 512L * 1024L

    @Test
    fun `only large non-gif images are re-encoded`() {
        assertTrue("a multi-MB jpeg must shrink", ImagePrep.shouldCompress("image/jpeg", 4L * 1024 * 1024))
        assertTrue("png counts", ImagePrep.shouldCompress("image/png", 2L * 1024 * 1024))
        assertTrue("the boundary is included", ImagePrep.shouldCompress("image/jpeg", halfMb))
        assertFalse("already small", ImagePrep.shouldCompress("image/jpeg", 200L * 1024))
        assertFalse("gifs may be animated", ImagePrep.shouldCompress("image/gif", 8L * 1024 * 1024))
        assertFalse("videos pass through", ImagePrep.shouldCompress("video/mp4", 20L * 1024 * 1024))
        assertFalse("unknown types pass through", ImagePrep.shouldCompress("application/octet-stream", 4L * 1024 * 1024))
        assertFalse("no type, no compression", ImagePrep.shouldCompress(null, 4L * 1024 * 1024))
    }

    @Test
    fun `the sample size keeps the longest edge at or above the cap`() {
        assertEquals(1, ImagePrep.sampleSizeFor(1920, 1080))
        assertEquals(1, ImagePrep.sampleSizeFor(1000, 800))
        assertEquals("exactly double still halves", 2, ImagePrep.sampleSizeFor(3840, 2160))
        assertEquals(2, ImagePrep.sampleSizeFor(4000, 3000))
        assertEquals("portrait works the same", 2, ImagePrep.sampleSizeFor(3000, 4000))
        assertEquals(4, ImagePrep.sampleSizeFor(8000, 1000))
        assertEquals("degenerate sizes are left alone", 1, ImagePrep.sampleSizeFor(0, 0))
        assertEquals(1, ImagePrep.sampleSizeFor(4000, 3000, maxDim = 0))
    }

    @Test
    fun `exif orientations map to the clockwise rotation bake`() {
        // Raw EXIF orientation tag values (ExifInterface mirrors them).
        assertEquals(90, ImagePrep.rotationDegrees(6)) // rotate 90 CW
        assertEquals(180, ImagePrep.rotationDegrees(3)) // rotate 180
        assertEquals(270, ImagePrep.rotationDegrees(8)) // rotate 270 CW
        assertEquals(90, ImagePrep.rotationDegrees(5)) // transpose
        assertEquals(270, ImagePrep.rotationDegrees(7)) // transverse
        assertEquals("normal is a no-op", 0, ImagePrep.rotationDegrees(1))
        assertEquals(0, ImagePrep.rotationDegrees(0)) // undefined
    }

    @Test
    fun `the budget constants stay phone-appropriate`() {
        assertEquals(1920, ImagePrep.MAX_DIMENSION)
        assertEquals(85, ImagePrep.JPEG_QUALITY)
        assertEquals(halfMb, ImagePrep.SKIP_UNDER_BYTES)
    }
}
