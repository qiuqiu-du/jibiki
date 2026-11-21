package app.jisho.core.userdata

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** How an imported CSV is encoded, as the user picks it. */
enum class CsvEncoding { Utf8, Ansi }

/**
 * Decodes imported text files. "ANSI" is what Excel on Windows calls the system code page:
 * GBK on Chinese Windows, Shift-JIS on Japanese Windows. The file doesn't say which, so both
 * are tried and the one that reads as Japanese/Chinese text wins (see [score]). A file that is
 * really UTF-8 is read as UTF-8 whichever option was picked, and vice versa.
 */
object TextEncoding {
    private val ANSI = listOf("GBK", "Shift_JIS", "windows-31j", "GB18030")

    fun decode(bytes: ByteArray, encoding: CsvEncoding): String {
        val hasBom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        if (hasBom) return strict(bytes, Charsets.UTF_8)!!.removePrefix("﻿")
        if (encoding == CsvEncoding.Utf8) strict(bytes, Charsets.UTF_8)?.let { return it }
        val ansi = ANSI.filter { Charset.isSupported(it) }
            .mapNotNull { name -> strict(bytes, Charset.forName(name)) }
            .maxByOrNull { score(it) }
        return ansi ?: strict(bytes, Charsets.UTF_8) ?: bytes.decodeToString()
    }

    /** Null if [bytes] aren't valid in [charset]. */
    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * How much a decoding looks like real text: kana and common kanji count for it; half-width
     * katakana (what GBK bytes turn into when read as Shift-JIS), private-use and rare
     * characters (what Shift-JIS turns into when read as GBK) count against it.
     */
    internal fun score(text: String): Int = text.sumOf { c ->
        when (c) {
            in '぀'..'ヿ' -> 3 // hiragana, katakana
            in '一'..'鿿' -> 1 // CJK ideographs
            in '｡'..'ﾟ' -> -4 // half-width katakana
            in ''..'', '�' -> -10
            in '㐀'..'䶿', in '⺀'..'⿿' -> -2 // rare ideographs, radicals
            else -> 0
        }.toInt()
    }
}
