package com.amethyst2213.operatorchat

import java.io.File

/**
 * MIME sniffing shared by the upload path (ChatBridge) and the media viewer.
 *
 * Pure byte-array logic on purpose: unit-testable without Android, and the
 * viewer needs the same detection for files downloaded without an extension
 * (`full_<id>_<ts>.img`).
 */
object MediaSniff {
    /** Content type for uploads. Unknown types fall back to the file name. */
    fun typeOf(file: File): String {
        val head = headOf(file, 16)
        val byMagic = magicType(head)
        if (byMagic != null) return byMagic

        val name = file.name.lowercase()
        return when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".webm") -> "video/webm"
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    /**
     * Viewer hint: the video container at the start of the file, or null when
     * the bytes are not a known video container. Deliberately broader than
     * [magicType] (the upload path only needs mp4) because the viewer plays
     * whatever actually arrived.
     */
    fun videoHint(file: File): String? = videoHint(headOf(file, 16))

    fun videoHint(head: ByteArray): String? {
        val hex = hex(head)
        return when {
            // ....ftyp — the box type sits at byte 4 (after the 4-byte size).
            hex.length >= 16 && hex.substring(8, 16) == "66747970" -> "video/mp4"
            hex.startsWith("1A45DFA3") -> "video/webm" // EBML (webm/mkv)
            // RIFF....AVI  — 'AVI ' sits at byte 8 (after RIFF + chunk size).
            hex.length >= 24 && hex.startsWith("52494646") && hex.substring(16, 24) == "41564920" -> "video/avi"
            else -> null
        }
    }

    /** The original magic-byte rules used for upload typing (jpeg/png/gif/webp/mp4). */
    private fun magicType(head: ByteArray): String? {
        val hex = hex(head)
        return when {
            hex.startsWith("FFD8FF") -> "image/jpeg"
            hex.startsWith("89504E47") -> "image/png"
            hex.startsWith("47494638") -> "image/gif"
            // RIFF....WEBP — the marker sits at byte 8, i.e. hex chars 16..23.
            // The previous code compared substring(16, 20) (4 chars) against
            // the 8-char "57454250", which could never match, so this branch
            // was dead and webp files fell through to the extension guess.
            hex.length >= 24 && hex.startsWith("52494646") && hex.substring(16, 24) == "57454250" -> "image/webp"
            hex.length >= 16 && hex.substring(8, 16) == "66747970" -> "video/mp4"
            else -> null
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { b -> String.format("%02X", b) }

    private fun headOf(file: File, max: Int): ByteArray {
        return try {
            file.inputStream().use { ins ->
                val buf = ByteArray(max)
                var read = 0
                while (read < buf.size) {
                    val n = ins.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }
}
