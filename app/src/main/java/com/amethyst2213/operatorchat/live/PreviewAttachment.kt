package com.amethyst2213.operatorchat.live

/**
 * Keeps the camera preview attached.
 *
 * The order is not what it looks like: the surface usually arrives BEFORE
 * the camera and the encoder are ready - they take 100-500ms to open - so
 * the surface has to be kept and attached when the encoder is there.
 *
 * A preview that is never attached is a black box, and the operator cannot
 * tell a dead camera from a dead stream. The old screen attached the
 * surface again whenever the encoder became ready, and dropped the stream
 * when the attach failed so that a later attempt could retry.
 *
 * @param T the surface type (a SurfaceTexture on a device).
 */
class PreviewAttachment<T> {

    private var handle: T? = null
    private var width = 0
    private var height = 0
    private var attached = false

    /**
     * The surface arrived (or changed size).
     *
     * The preview is attached again: a new surface means a new attach.
     */
    fun setSurface(handle: T, width: Int, height: Int) {
        this.handle = handle
        this.width = width
        this.height = height
        this.attached = false
    }

    /**
     * The encoder is ready: attach the surface if there is one.
     *
     * [attach] returns true when the surface was taken. A false return
     * leaves the preview unattached, and the next call tries again.
     */
    fun attachWhenReady(attach: (T, Int, Int) -> Boolean) {
        val surface = handle ?: return
        if (attached) return
        if (attach(surface, width, height)) {
            attached = true
        }
    }

    /** The encoder is gone: the surface has to be attached again later. */
    fun detach() {
        attached = false
    }

    /** Forget the surface completely. */
    fun clear() {
        handle = null
        width = 0
        height = 0
        attached = false
    }

    /** True when the surface is attached to the encoder. */
    val isAttached: Boolean get() = attached

    /** True when there is a surface waiting to be attached. */
    val hasSurface: Boolean get() = handle != null
}
