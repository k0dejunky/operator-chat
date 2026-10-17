package com.amethyst2213.operatorchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The upload sniffer and the viewer hint share [MediaSniff]; both feed on the
 * first bytes of a file, so a wrong offset silently mis-types attachments.
 * These tests pin the real byte layouts (ftyp at byte 4, RIFF markers at
 * byte 8) — the previous inline code compared a 4-char substring against an
 * 8-char hex constant and could never match, so webp/mp4 magic was dead and
 * everything fell through to the file extension.
 */
class MediaSniffTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun tempFile(name: String, data: ByteArray): File {
        val f = File.createTempFile("sniff-", "-$name")
        f.writeBytes(data)
        f.deleteOnExit()
        return f
    }

    // 00 00 00 20 'f' 't' 'y' 'p' 'i' 's' 'o' 'm'  — size + ftyp at byte 4.
    private val mp4Head = bytes(
        0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D,
    )

    // 'R' 'I' 'F' 'F' + 4 size bytes + 'W' 'E' 'B' 'P'  — marker at byte 8.
    private val webpHead = bytes(
        0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50,
    )

    // RIFF + size + 'A' 'V' 'I' ' '  — same offsets, the AVI variant.
    private val aviHead = bytes(
        0x52, 0x49, 0x46, 0x46, 0x10, 0x00, 0x00, 0x00, 0x41, 0x56, 0x49, 0x20,
    )

    private val ebmlHead = bytes(
        0x1A, 0x45, 0xDF, 0xA3, 0x93, 0x42, 0x86, 0x81, 0x01, 0x42, 0xF7, 0x81,
    )

    private val jpegHead = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)

    private val pngHead = bytes(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
    )

    private val gifHead = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00)

    @Test
    fun `magic bytes type the common media formats`() {
        // Names that do NOT carry the format extension, so only the sniffing
        // (not the fallback) can produce the answer.
        assertEquals("image/jpeg", MediaSniff.typeOf(tempFile("pic.bin", jpegHead)))
        assertEquals("image/png", MediaSniff.typeOf(tempFile("pic.bin", pngHead)))
        assertEquals("image/gif", MediaSniff.typeOf(tempFile("pic.bin", gifHead)))
        assertEquals("image/webp", MediaSniff.typeOf(tempFile("pic.bin", webpHead)))
        assertEquals("video/mp4", MediaSniff.typeOf(tempFile("clip.bin", mp4Head)))
    }

    @Test
    fun `unknown bytes fall back to the extension, then to octet-stream`() {
        val zeros = ByteArray(16)
        assertEquals("application/pdf", MediaSniff.typeOf(tempFile("report.pdf", zeros)))
        assertEquals("text/plain", MediaSniff.typeOf(tempFile("notes.txt", zeros)))
        assertEquals("application/octet-stream", MediaSniff.typeOf(tempFile("blob.bin", zeros)))
    }

    @Test
    fun `the viewer hint only claims known video containers`() {
        assertEquals("video/mp4", MediaSniff.videoHint(mp4Head))
        assertEquals("video/webm", MediaSniff.videoHint(ebmlHead))
        assertEquals("video/avi", MediaSniff.videoHint(aviHead))

        assertNull("a jpeg must not be treated as video", MediaSniff.videoHint(jpegHead))
        assertNull("a webp must not be treated as video", MediaSniff.videoHint(webpHead))
    }

    @Test
    fun `short and empty files never crash the sniffer`() {
        assertNull(MediaSniff.videoHint(ByteArray(0)))
        assertNull(MediaSniff.videoHint(bytes(0x52, 0x49, 0x46, 0x46)))
        assertEquals(
            "application/octet-stream",
            MediaSniff.typeOf(tempFile("empty.bin", ByteArray(0))),
        )
    }

    @Test
    fun `the viewer hint reads the file itself`() {
        // The overload the media viewer uses — proves the offset logic runs
        // against a real stream, not just an in-memory array.
        assertEquals("video/mp4", MediaSniff.videoHint(tempFile("clip.bin", mp4Head)))
        assertNull(MediaSniff.videoHint(tempFile("pic.bin", jpegHead)))
    }
}
