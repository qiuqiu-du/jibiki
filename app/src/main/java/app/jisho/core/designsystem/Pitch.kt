package app.jisho.core.designsystem

/** "2" -> "②", "0,2" -> "⓪②", "1+1" -> "[1+1]". */
fun formatAccent(accent: String?): String {
    if (accent.isNullOrEmpty()) return ""
    return accent.split(',').joinToString("") { part ->
        val n = part.toIntOrNull()
        when {
            n == null -> "[$part]"
            n == 0 -> "⓪"
            n in 1..20 -> (0x2460 + n - 1).toChar().toString()
            else -> "[$n]"
        }
    }
}
