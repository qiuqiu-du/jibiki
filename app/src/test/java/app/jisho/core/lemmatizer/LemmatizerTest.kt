package app.jisho.core.lemmatizer

import app.jisho.testing.TestPacks
import app.jisho.core.designsystem.ReasonLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lemmatizers (design-spec 4.2): the rule table, Kuromoji, JMdict validation, and the
 * 500+ case test set (src/test/resources/lemma_cases.tsv) run in both modes.
 * The default mode (Kuromoji) must pass every case; the rule-table mode is reported.
 */
class LemmatizerTest {

    private data class Case(val input: String, val lemma: String, val category: String)

    private val cases by lazy {
        TestPacks.resourceLines("lemma_cases.tsv").map { it.split('\t').let { c -> Case(c[0], c[1], c[2]) } }
    }

    private fun lemmatizer(mode: LemmatizerMode): Lemmatizer = when (mode) {
        LemmatizerMode.Rules -> rules
        LemmatizerMode.Kuromoji -> TestPacks.combined
    }

    /** Spellings and readings of the validated lemmas for [input]. */
    private fun lemmas(mode: LemmatizerMode, input: String): List<Set<String>> {
        val validated = LemmaValidator(TestPacks.sharedCore).validate(lemmatizer(mode).lemmatize(input))
        return validated.map { v ->
            TestPacks.sharedCore.entry(v.entryId)!!.forms.flatMap { listOfNotNull(it.kanji, it.reading) }.toSet()
        }
    }

    private fun runTestSet(mode: LemmatizerMode): List<Case> {
        TestPacks.assumePresent()
        val results = cases.associateWith { lemmas(mode, it.input) }
        val failures = cases.filter { c -> results[c]!!.none { c.lemma in it } }
        // Top-1 only means something for inputs with a single correct lemma.
        val single = cases.groupBy { it.input }.filterValues { it.size == 1 }.values.map { it[0] }
        val top1 = single.count { c -> results[c]!!.firstOrNull()?.contains(c.lemma) == true }
        println("lemma top-1, $mode: $top1/${single.size}; average candidates %.1f".format(results.values.map { it.size }.average()))
        single.filter { c -> results[c]!!.firstOrNull()?.contains(c.lemma) != true }.take(40)
            .forEach { c -> println("  NOT-FIRST $mode ${c.input} -> ${c.lemma}: first ${results[c]!!.firstOrNull()?.take(2)}") }
        val byCategory = cases.groupBy { it.category }.map { (cat, cs) ->
            "$cat ${cs.size - failures.count { it.category == cat }}/${cs.size}"
        }
        println("lemma test set, $mode: ${cases.size - failures.size}/${cases.size} " +
            "(%.1f%%)  ".format(100.0 * (cases.size - failures.size) / cases.size) + byCategory.joinToString(", "))
        failures.forEach { println("  FAIL $mode ${it.input} -> ${it.lemma} (${it.category})") }
        return failures
    }

    @Test fun testSetHasAtLeast500Cases() = assertTrue(cases.size >= 500)

    @Test fun defaultModePassesWholeTestSet() = assertEquals(emptyList<Case>(), runTestSet(LemmatizerMode.Kuromoji))

    @Test fun ruleModeTestSet() = assertEquals(emptyList<Case>(), runTestSet(LemmatizerMode.Rules))

    @Test fun ruleTableUndoesStepsInOrder() {
        val c = rules.lemmatize("食べなかった").first { it.lemma == "食べる" }
        assertEquals(listOf("neg", "past"), c.reasons)
        assertEquals(setOf("v1", "vk"), c.types)
    }

    @Test fun ambiguousFormsListEveryReading() {
        TestPacks.assumePresent()
        val found = lemmas(LemmatizerMode.Rules, "いった").map { it }
        for (lemma in listOf("行く", "言う", "要る")) assertTrue("いった -> $lemma", found.any { lemma in it })
    }

    @Test fun validationChecksPartOfSpeech() {
        TestPacks.assumePresent()
        // いった can be いる only as a godan verb (要る), never the ichidan 居る.
        val validator = LemmaValidator(TestPacks.sharedCore)
        val ids = validator.validate(rules.lemmatize("いった")).filter { it.candidate.lemma == "いる" }
        assertTrue(ids.isNotEmpty())
        ids.forEach { v -> assertTrue("v5r" in TestPacks.sharedCore.entry(v.entryId)!!.pos) }
        // A made-up form produces nothing.
        assertEquals(emptyList<ValidatedLemma>(), validator.validate(rules.lemmatize("ぬぬぬなかった")))
    }

    @Test fun honorificPrefixNeedsHonorificEnding() {
        TestPacks.assumePresent()
        assertTrue(rules.lemmatize("お読みになりました").any { it.lemma == "読む" && "honorific" in it.reasons })
        // おきた is 起きる, not お + 着る/来る: the prefix is only dropped with an honorific frame.
        assertTrue(rules.lemmatize("おきた").none { it.lemma == "きる" || it.lemma == "くる" })
    }

    @Test fun segmentsSentenceIntoContentWords() {
        val segments = kuromoji.segment("昨日は本を読まなかった")
        assertEquals(listOf("昨日", "本", "読まなかった"), segments.map { it.surface })
        assertEquals("読む", segments.last().lookup)
    }

    /** Kuromoji only finds the lemma; the rule table must reach the same lemma, or it is dropped. */
    @Test fun ruleTableReachesEveryKuromojiReadingInTestSet() {
        TestPacks.assumePresent()
        val missed = cases.map { it.input }.distinct().flatMap { input ->
            val ruled = rules.lemmatize(input).map { it.lemma }.toSet()
            kuromoji.lemmatize(input).filter { it.lemma !in ruled }.map { "$input -> ${it.lemma}" }
        }
        assertEquals(emptyList<String>(), missed)
    }

    /** Extra kana after an inflected form is not an inflection (食べないあ gives no lemma row). */
    @Test fun trailingJunkGivesNoLemma() {
        TestPacks.assumePresent()
        val validator = LemmaValidator(TestPacks.sharedCore)
        val found = cases.map { it.input }.distinct().flatMap { input ->
            listOf("ぁ", "ゑ", "ヶ").flatMap { junk ->
                validator.validate(TestPacks.combined.lemmatize(input + junk)).map { "$input$junk -> ${it.candidate.lemma}" }
            }
        }
        assertEquals(emptyList<String>(), found)
    }

    /** Every candidate row for an inflected input says which inflection was undone. */
    @Test fun everyLemmaRowHasLabels() {
        TestPacks.assumePresent()
        val validator = LemmaValidator(TestPacks.sharedCore)
        for (mode in LemmatizerMode.entries) {
            val inputs = cases.map { it.input }.distinct().flatMap { listOf(it, it + "あ", it + "よ", it + "か") }
            val bare = inputs.flatMap { input ->
                validator.validate(lemmatizer(mode).lemmatize(input))
                    .filter { it.candidate.reasons.isEmpty() }.map { "$mode $input -> ${it.candidate.lemma}" }
            }
            assertEquals(emptyList<String>(), bare)
        }
    }

    /** Auxiliary verbs are words of their own in the word bar, as dictionaries treat them. */
    @Test fun auxiliaryVerbsAreSeparateWords() {
        for ((input, words) in listOf(
            "食べてくる" to listOf("食べて" to "食べる", "くる" to "くる"),
            "増えていく" to listOf("増えて" to "増える", "いく" to "いく"),
            "決めかねる" to listOf("決め" to "決める", "かねる" to "かねる"),
            "読み終わった" to listOf("読み" to "読む", "終わった" to "終わる"),
        )) assertEquals(input, words, kuromoji.segment(input).map { it.surface to it.lookup })
    }

    /** An honorific frame needs its prefix, and dropping a prefix ranks after direct readings. */
    @Test fun honorificFrameNeedsPrefix() {
        TestPacks.assumePresent()
        assertTrue(rules.lemmatize("おいしそう").none { !it.dropsPrefix && HONORIFIC_KEY in it.reasons })
        val validator = LemmaValidator(TestPacks.sharedCore)
        for (mode in LemmatizerMode.entries) {
            assertEquals(mode.name, "おいしい", validator.validate(lemmatizer(mode).lemmatize("おいしそう")).first().candidate.lemma)
        }
        // Real honorific forms still work.
        assertTrue(validator.validate(rules.lemmatize("お読みになりました")).any { it.candidate.lemma == "読む" })
    }

    /** Over-built readings (lemma_rejects.tsv) never come out, in either mode. */
    @Test fun overBuiltReadingsAreRejected() {
        TestPacks.assumePresent()
        val rejects = TestPacks.resourceLines("lemma_rejects.tsv").map { it.split('\t') }
        assertTrue(rejects.size >= 15)
        val found = LemmatizerMode.entries.flatMap { mode ->
            rejects.filter { (input, lemma) -> lemmas(mode, input).any { lemma in it } }.map { "$mode ${it[0]} -> ${it[1]}" }
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test fun everyReasonHasALabel() {
        val table = RuleLemmatizer.parse(File(TestPacks.RULES).readText())
        val keys = table.rules.flatMap { it.reasons }.toSet() + table.raw.values.filterNotNull()
        keys.forEach { assertTrue("no label for inflection key $it", it in ReasonLabels) }
    }

    @Test fun ruleTableHasNoBrokenLines() {
        val table = RuleLemmatizer.parse(File(TestPacks.RULES).readText())
        assertTrue(table.rules.size > 100)
        table.rules.forEach { r -> assertTrue("rule $r needs output types", r.typesOut.isNotEmpty()) }
        assertEquals(listOf("お", "ご", "御"), table.prefixes)
    }

    private val rules get() = TestPacks.rules
    private val kuromoji get() = TestPacks.kuromoji

    private companion object {
        const val HONORIFIC_KEY = RuleLemmatizer.HONORIFIC
    }
}
