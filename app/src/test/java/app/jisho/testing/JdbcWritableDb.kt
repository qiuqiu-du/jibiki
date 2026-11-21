package app.jisho.testing

import app.jisho.core.database.SqlRow
import app.jisho.core.database.WritableDb
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** JDBC-backed [WritableDb] for JVM tests; [file] null = in memory. */
class JdbcWritableDb(file: File? = null) : WritableDb {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:" + (file?.path ?: ":memory:"))
    private var depth = 0

    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun exec(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.execute()
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth++ == 0) conn.autoCommit = false
        try {
            val r = block()
            if (depth == 1) conn.commit()
            return r
        } catch (e: Throwable) {
            if (depth == 1) conn.rollback()
            throw e
        } finally {
            if (--depth == 0) conn.autoCommit = true
        }
    }

    override fun close() = conn.close()

    private class Row(private val rs: ResultSet) : SqlRow {
        override fun long(i: Int) = rs.getLong(i + 1)
        override fun int(i: Int) = rs.getInt(i + 1)
        override fun string(i: Int): String = rs.getString(i + 1)
        override fun stringOrNull(i: Int): String? = rs.getString(i + 1)
        override fun blob(i: Int): ByteArray = rs.getBytes(i + 1)
    }
}
