package app.jisho.core.database

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** [WritableDb] on Android's SQLite; creates the file if it doesn't exist. */
class AndroidWritableDb(file: File) : WritableDb {
    private val db = SQLiteDatabase.openDatabase(
        file.path, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            val row = AndroidSqlDb.CursorRow(c)
            buildList { while (c.moveToNext()) add(map(row)) }
        }

    override fun exec(sql: String, args: List<Any?>) = db.execSQL(sql, args.toTypedArray())

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()
}
