package com.example.rootguide

// מדליק את דגלי AVB בכותרת vbmeta: 1 = hashtree כבוי, 2 = אימות כבוי.
// הדגלים נמצאים בבייטים 120-123 (big endian). מחזיר null אם זה לא קובץ vbmeta תקין
fun disableAvbFlags(vbmeta: ByteArray): ByteArray? {
    if (vbmeta.size < 124) return null
    if (String(vbmeta, 0, 4, Charsets.US_ASCII) != "AVB0") return null
    val out = vbmeta.copyOf()
    val flags = ((out[120].toInt() and 0xFF) shl 24) or
        ((out[121].toInt() and 0xFF) shl 16) or
        ((out[122].toInt() and 0xFF) shl 8) or
        (out[123].toInt() and 0xFF)
    val newFlags = flags or 3
    out[120] = (newFlags ushr 24).toByte()
    out[121] = (newFlags ushr 16).toByte()
    out[122] = (newFlags ushr 8).toByte()
    out[123] = newFlags.toByte()
    return out
}

fun fmtMb(bytes: Long?): String =
    if (bytes == null) "?" else "%.1f MB".format(bytes / 1048576.0)
