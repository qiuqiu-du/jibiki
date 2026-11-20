package app.jisho.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.jisho.R

/** Inflection label keys (assets/deinflect_rules.tsv) -> UI strings. */
val ReasonLabels: Map<String, Int> = mapOf(
    "adverbial" to R.string.lemma_reason_adverbial,
    "attributive" to R.string.lemma_reason_attributive,
    "become" to R.string.lemma_reason_become,
    "causative" to R.string.lemma_reason_causative,
    "causative_passive" to R.string.lemma_reason_causative_passive,
    "cond" to R.string.lemma_reason_cond,
    "copula" to R.string.lemma_reason_copula,
    "contraction" to R.string.lemma_reason_contraction,
    "even" to R.string.lemma_reason_even,
    "finish" to R.string.lemma_reason_finish,
    "honorific" to R.string.lemma_reason_honorific,
    "imperative" to R.string.lemma_reason_imperative,
    "must" to R.string.lemma_reason_must,
    "neg" to R.string.lemma_reason_neg,
    "passive" to R.string.lemma_reason_passive,
    "passive_potential" to R.string.lemma_reason_passive_potential,
    "past" to R.string.lemma_reason_past,
    "polite" to R.string.lemma_reason_polite,
    "potential" to R.string.lemma_reason_potential,
    "prepare" to R.string.lemma_reason_prepare,
    "progressive" to R.string.lemma_reason_progressive,
    "request" to R.string.lemma_reason_request,
    "resultative" to R.string.lemma_reason_resultative,
    "seems" to R.string.lemma_reason_seems,
    "stem" to R.string.lemma_reason_stem,
    "tara" to R.string.lemma_reason_tara,
    "tari" to R.string.lemma_reason_tari,
    "te" to R.string.lemma_reason_te,
    "too" to R.string.lemma_reason_too,
    "try" to R.string.lemma_reason_try,
    "volitional" to R.string.lemma_reason_volitional,
    "want" to R.string.lemma_reason_want,
    "while" to R.string.lemma_reason_while,
)

/** "否定・过去" for [neg, past]; unknown keys are skipped. */
@Composable
fun reasonText(reasons: List<String>): String =
    reasons.mapNotNull { ReasonLabels[it] }.map { stringResource(it) }.distinct().joinToString("・")
