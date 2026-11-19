package app.jisho.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

class AndroidSqlDb(file: File) : SqlDb {
    private val db = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            val row = CursorRow(c)
            buildList { while (c.moveToNext()) add(map(row)) }
        }

    override fun close() = db.close()

    private class CursorRow(private val c: Cursor) : SqlRow {
        override fun long(i: Int) = c.getLong(i)
        override fun int(i: Int) = c.getInt(i)
        override fun string(i: Int): String = c.getString(i)
        override fun stringOrNull(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
        override fun blob(i: Int): ByteArray = c.getBlob(i)
    }
}
