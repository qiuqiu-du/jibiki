package app.jisho.testing

import app.jisho.core.data.DictionaryRepository
import app.jisho.core.data.PackOpener
import app.jisho.core.lemmatizer.CombinedLemmatizer
import app.jisho.core.lemmatizer.KuromojiLemmatizer
import app.jisho.core.lemmatizer.RuleLemmatizer
import com.atilika.kuromoji.ipadic.Tokenizer
import app.jisho.core.database.CoreDb
import app.jisho.core.database.DictDb
import app.jisho.core.database.SqlDb
import app.jisho.core.database.SqlRow
import org.junit.Assume.assumeTrue
import java.io.File
import java.sql.Connection
import org.sqlite.SQLiteConfig
import java.sql.ResultSet

/** JDBC-backed [SqlDb] so data-layer code runs on the JVM against the real packs. */
class JdbcSqlDb(file: File) : SqlDb {
    private val conn: Connection = SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:${file.path}")

    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> {
        // Android binds "?1" style indexes; JDBC needs plain "?" in order, so expand them.
        val order = mutableListOf<Int>()
        var next = 0
        val jdbcSql = Regex("""\?(\d*)""").replace(sql) { m ->
            order += if (m.groupValues[1].isEmpty()) next++ else m.groupValues[1].toInt() - 1
            "?"
        }
        conn.prepareStatement(jdbcSql).use { st ->
            order.forEachIndexed { i, argIndex -> st.setString(i + 1, args[argIndex]) }
            st.executeQuery().use { rs ->
                val row = ResultSetRow(rs)
                return buildList { while (rs.next()) add(map(row)) }
            }
        }
    }

    override fun close() = conn.close()

    private class ResultSetRow(private val rs: ResultSet) : SqlRow {
        override fun long(i: Int) = rs.getLong(i + 1)
        override fun int(i: Int) = rs.getInt(i + 1)
        override fun string(i: Int): String = rs.getString(i + 1)
        override fun stringOrNull(i: Int): String? = rs.getString(i + 1)
        override fun blob(i: Int): ByteArray = rs.getBytes(i + 1)
    }
}

/** Access to datapacks/ for tests. Tests that need it are skipped when the packs are absent. */
object TestPacks {
    val dir = File(System.getProperty("datapacks.dir") ?: "../datapacks")
    val coreFile = File(dir, "core.db")
    val dictionaryIds = listOf("daijisen", "meikyo3", "shinmeikai8")

    fun assumePresent() = assumeTrue("data packs not generated (run tools/build_*.py)", coreFile.exists())

    fun core(usuallyKanaMask: Int = 1) = CoreDb(JdbcSqlDb(coreFile), usuallyKanaMask)

    fun dict(id: String) = DictDb(JdbcSqlDb(File(dir, "dict_$id.db")), id)

    fun opener(usuallyKanaMask: Int = 1) = object : PackOpener {
        override fun openCore() = core(usuallyKanaMask)
        override fun openDictionaries() = dictionaryIds.filter { File(dir, "dict_$it.db").exists() }.map { dict(it) }
    }

    /** Shared instances for read-only tests (opening packs is the slow part). */
    val sharedCore: CoreDb by lazy { core() }
    val sharedDicts: List<DictDb> by lazy { opener().openDictionaries() }

    /** Lemmatizers built from the app's real rule table (src/main/assets). */
    val rules by lazy { RuleLemmatizer(RuleLemmatizer.parse(File(RULES).readText())) }
    val kuromoji by lazy { KuromojiLemmatizer(Tokenizer()) }
    val combined by lazy { CombinedLemmatizer({ kuromoji }, rules) }
    const val RULES = "src/main/assets/deinflect_rules.tsv"

    /** A repository set up like the app in the default (Kuromoji) mode. */
    fun repositoryWithLemmatizer() = DictionaryRepository(opener(), { combined }, { kuromoji })

    fun resourceLines(name: String): List<String> =
        TestPacks::class.java.classLoader!!.getResource(name)!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
}
