package app.jisho.core.database

/**
 * Minimal read-only SQL access. The app uses Android's SQLite ([AndroidSqlDb]);
 * JVM tests use a JDBC implementation, so query and ranking logic can be tested
 * on the computer against the real data packs, without a phone.
 *
 * All arguments are bound as TEXT (Android's rawQuery only supports strings), so
 * numeric values must be written into the SQL itself, never bound.
 */
interface SqlDb : AutoCloseable {
    fun <T> query(sql: String, args: List<String> = emptyList(), map: (SqlRow) -> T): List<T>
}

interface SqlRow {
    fun long(i: Int): Long
    fun int(i: Int): Int
    fun string(i: Int): String
    fun stringOrNull(i: Int): String?
    fun blob(i: Int): ByteArray
}

fun SqlDb.meta(key: String): String? =
    query("SELECT value FROM meta WHERE key = ?", listOf(key)) { it.string(0) }.firstOrNull()

/** A database the app writes (user.db, the Anki export). Args: String, Long, Int, ByteArray or null. */
interface WritableDb : SqlDb {
    fun exec(sql: String, args: List<Any?> = emptyList())
    /** Runs [block] in one transaction: all its writes happen, or (on an exception) none. */
    fun <T> transaction(block: () -> T): T
}
