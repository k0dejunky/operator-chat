package com.amethyst2213.operatorchat.live

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground service type the broadcast service is promoted with.
 *
 * On Android 14+ a foreground service that is promoted with the camera or
 * the microphone type while that permission is NOT held throws a
 * SecurityException. The service was started before the operator had ever
 * been asked for the camera, so opening the live screen could crash the app.
 */
class ForegroundTypesTest {

    @Test
    fun `without any permission only the data sync type is used`() {
        val type = LiveBroadcastService.foregroundTypes(cameraGranted = false, micGranted = false)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, type)
    }

    @Test
    fun `the camera type is only used when the camera is granted`() {
        val type = LiveBroadcastService.foregroundTypes(cameraGranted = true, micGranted = false)
        assertTrue("the camera type is missing", type and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0)
        assertTrue(
            "the microphone type is not granted yet",
            type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0,
        )
    }

    @Test
    fun `the microphone type is only used when the microphone is granted`() {
        val type = LiveBroadcastService.foregroundTypes(cameraGranted = false, micGranted = true)
        assertTrue("the microphone type is missing", type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        assertTrue(
            "the camera type is not granted yet",
            type and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA == 0,
        )
    }

    @Test
    fun `both permissions give both types`() {
        val type = LiveBroadcastService.foregroundTypes(cameraGranted = true, micGranted = true)
        assertTrue(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0)
        assertTrue(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        assertTrue(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }
}
