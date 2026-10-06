package com.zhousl.aether.agentmode

/** How many consecutive identical samples count as "the screen stopped changing". */
internal const val AgentModeSettleStableSamples = 2

/**
 * Interval between samples. Short enough to notice a transition that finishes quickly, long enough
 * that the repeated reads do not become the slowest part of an action.
 */
internal const val AgentModeSettleSampleIntervalMillis = 70L

/** How long a normal action waits for the screen to settle before reporting that it did not. */
internal const val AgentModeSettleTimeoutMillis = 1_500L

/** A cold app start produces its first window far later than an in-page transition does. */
internal const val AgentModeLaunchSettleTimeoutMillis = 5_000L

/**
 * A cheap description of what is currently on the display.
 *
 * Built from signals that cost one accessibility read rather than a screenshot, because this is
 * sampled several times per action. It answers two questions with the same value: whether the screen
 * has stopped changing, and whether an action changed anything at all.
 */
internal data class AgentModeScreenSignature(
    val signals: Map<String, String>,
) {
    fun stableKey(): String = signals.entries.joinToString("|") { entry -> entry.key + '=' + entry.value }

    companion object {
        val Empty = AgentModeScreenSignature(emptyMap())
    }
}

internal enum class AgentModeEffect { Changed, Unchanged, Unknown }

internal data class AgentModeEffectReport(
    val effect: AgentModeEffect,
    /** Which signals differed, so a report of "changed" can be audited rather than trusted. */
    val changedSignals: List<String>,
)

/**
 * Compares the screen before and after an action.
 *
 * Both sides missing reports [AgentModeEffect.Unknown] rather than "unchanged": without a signal the
 * honest answer is that nothing is known, and a model that is told "unchanged" would retry an action
 * that may well have worked.
 */
internal fun compareAgentModeScreenSignatures(
    before: AgentModeScreenSignature?,
    after: AgentModeScreenSignature?,
): AgentModeEffectReport {
    if (before == null || after == null) {
        return AgentModeEffectReport(AgentModeEffect.Unknown, emptyList())
    }
    if (before.signals == after.signals) {
        return AgentModeEffectReport(AgentModeEffect.Unchanged, emptyList())
    }
    val changed = (before.signals.keys + after.signals.keys)
        .filter { key -> before.signals[key] != after.signals[key] }
        .sorted()
    return AgentModeEffectReport(AgentModeEffect.Changed, changed)
}

/**
 * Decides when a screen has stopped changing.
 *
 * The tracker only owns the decision; sampling and sleeping stay with the caller so the rule can be
 * unit-tested without a device, and so the same rule can be reused for the post-action effect report.
 */
internal class AgentModeSettleTracker(
    private val requiredStableSamples: Int = AgentModeSettleStableSamples,
) {
    var samples: Int = 0
        private set

    var isSettled: Boolean = false
        private set

    private var previousKey: String? = null
    private var stableRun: Int = 0

    fun record(signature: AgentModeScreenSignature) {
        val key = signature.stableKey()
        samples++
        stableRun = if (key == previousKey) stableRun + 1 else 0
        previousKey = key
        if (stableRun + 1 >= requiredStableSamples) isSettled = true
    }
}

/**
 * Builds the signal set from one accessibility read.
 *
 * The rendered labels are part of the signature because a screen can change without its window count
 * or its focus changing - a list refreshing in place, a button enabling - and those are exactly the
 * transitions a fixed delay used to cover for. The head of the list is enough to notice them without
 * making the sample itself expensive.
 */
internal fun agentModeScreenSignature(
    windowCount: Int,
    elements: List<AgentModeElement>,
): AgentModeScreenSignature {
    val focused = elements.firstOrNull { it.signals.focused }
    return AgentModeScreenSignature(
        mapOf(
            "windows" to windowCount.toString(),
            "nodes" to elements.size.toString(),
            "focus" to focusedSummary(focused),
            "head" to elements.take(AgentModeScreenSignatureHeadElements)
                .joinToString(",") { shortClassName(it.className) + ':' + it.label },
        ),
    )
}

private fun focusedSummary(focused: AgentModeElement?): String =
    if (focused == null) "-" else shortClassName(focused.className) + ':' + focused.label

/** How many leading elements take part in the content signal; more would only cost time. */
internal const val AgentModeScreenSignatureHeadElements = 12

internal data class AgentModeSettleReport(
    val settled: Boolean,
    val elapsedMillis: Long,
    val samples: Int,
    /** Which signal the decision was built from: "elements", "focused_window" or "none". */
    val signal: String,
)
