package com.amethyst2213.operatorchat.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera preview, in the order it really happens.
 *
 * The surface arrives BEFORE the camera and the encoder are ready - they
 * take 100-500ms to open - so the surface has to be kept and attached when
 * the encoder is there. The preview used to be a black box on a real
 * phone for exactly that reason: the surface was wiped by the teardown
 * that precedes the encoder and never attached.
 */
class PreviewAttachmentTest {

    @Test
    fun `a surface that arrives first is attached when the encoder is ready`() {
        val preview = PreviewAttachment<String>()
        val attached = mutableListOf<String>()

        preview.setSurface("surface", 720, 1280)
        // The encoder is not there yet: nothing to attach to.
        preview.attachWhenReady { _, _, _ -> false }
        assertFalse(preview.isAttached)

        // Now the encoder is ready.
        preview.attachWhenReady { surface, width, height ->
            attached += "$surface ${width}x$height"
            true
        }
        assertTrue(preview.isAttached)
        assertTrue(attached.contains("surface 720x1280"))
    }

    @Test
    fun `a failed attach is tried again`() {
        val preview = PreviewAttachment<String>()

        preview.setSurface("surface", 720, 1280)
        // The encoder is there but the attach failed.
        preview.attachWhenReady { _, _, _ -> false }
        assertFalse(preview.isAttached)

        var tries = 0
        preview.attachWhenReady { _, _, _ ->
            tries++
            true
        }
        assertTrue("the preview was never attached again", preview.isAttached)
        assertTrue("the attach was not retried", tries >= 1)
    }

    @Test
    fun `the surface is attached only once`() {
        val preview = PreviewAttachment<String>()
        var attaches = 0

        preview.setSurface("surface", 720, 1280)
        preview.attachWhenReady { _, _, _ -> attaches++; true }
        preview.attachWhenReady { _, _, _ -> attaches++; true }
        assertTrue(preview.isAttached)
        assertTrue("the surface was attached twice", attaches == 1)
    }

    @Test
    fun `a new surface is attached again`() {
        val preview = PreviewAttachment<String>()
        var attaches = 0

        preview.setSurface("first", 720, 1280)
        preview.attachWhenReady { _, _, _ -> attaches++; true }
        // The system gives us a new surface (the view was recreated).
        preview.setSurface("second", 1080, 1920)
        preview.attachWhenReady { _, _, _ -> attaches++; true }
        assertTrue("the new surface was not attached", attaches == 2)
    }

    @Test
    fun `the encoder going away means the preview is attached again`() {
        val preview = PreviewAttachment<String>()
        var attaches = 0

        preview.setSurface("surface", 720, 1280)
        preview.attachWhenReady { _, _, _ -> attaches++; true }
        // The stream is rebuilt: the surface has to be attached to the new
        // encoder.
        preview.detach()
        assertFalse(preview.isAttached)
        preview.attachWhenReady { _, _, _ -> attaches++; true }
        assertTrue("the surface was not attached to the new encoder", attaches == 2)
    }
}
