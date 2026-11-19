package app.jisho

import app.jisho.core.designsystem.LabelStyle

/**
 * Debug-build-only launch options, so on-device checks don't need taps or reinstalls:
 *   adb shell am start -S -n app.jisho/.MainActivity --es q 食べる          (pre-filled search)
 *   ... --es open meikyo3:51920                                           (open a dictionary entry)
 *   ... --es labelStyle Bracket                                           (compare label styles)
 */
object DebugOptions {
    @Volatile var labelStyle: LabelStyle? = null
}
