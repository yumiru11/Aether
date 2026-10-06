package com.zhousl.aether.agentmode

import android.view.accessibility.AccessibilityNodeInfo

/**
 * What happened to a text write. The issue that asked for write read-back asked for exactly these
 * three outcomes to be told apart: written as asked, written but not as asked, or not written at all,
 * with "the field could not be found" as its own answer rather than a generic error.
 */
internal enum class AgentModeTextVerification(val storageValue: String) {
    /** The field now holds what was asked for. */
    Matched("matched"),

    /** A password-style field reports a mask instead of the text; the write is accepted. */
    Masked("masked"),

    /** The field holds something else than what was asked for. */
    Mismatch("mismatch"),

    /** The field refused the write. */
    Rejected("rejected"),

    /** The write may have worked, but the field could not be read back. */
    Unverified("unverified"),

    /** No editable field was on screen to write into. */
    NoField("no_field"),

    /** The element the model pointed at is not an input. */
    NotEditable("not_editable"),
}

/** Characters apps use to mask a password field; the set is small and stable across skins. */
private val AgentModeMaskCharacters = charArrayOf('\u2022', '*', '\u25CF', '\u25CB', '\u00B7')

/**
 * Classifies a text write from the field's own answer.
 *
 * A field that was never refreshed, or whose text cannot be read, reports [Unverified] rather than
 * [Matched]: claiming success on no evidence is how a write that silently went nowhere used to reach
 * the model as an "ok" result.
 */
internal fun classifyAgentModeTextWrite(
    expected: String,
    observed: String?,
    nodeRefreshed: Boolean,
    actionAccepted: Boolean,
): AgentModeTextVerification = when {
    !actionAccepted -> AgentModeTextVerification.Rejected
    !nodeRefreshed -> AgentModeTextVerification.Unverified
    observed == null -> AgentModeTextVerification.Unverified
    observed == expected -> AgentModeTextVerification.Matched
    isMaskedAgentModeText(observed) -> AgentModeTextVerification.Masked
    else -> AgentModeTextVerification.Mismatch
}

internal fun isMaskedAgentModeText(observed: String): Boolean =
    observed.isNotEmpty() && observed.all { character -> character in AgentModeMaskCharacters }

internal enum class AgentModeScrollDirection(val storageValue: String) {
    Up("up"),
    Down("down"),
    Left("left"),
    Right("right"),
    ;

    companion object {
        fun fromStorage(value: String?): AgentModeScrollDirection? =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() }
    }
}

/** The accessibility action that scrolls a container one step in [direction]. */
internal fun agentModeScrollAction(direction: AgentModeScrollDirection): Int = when (direction) {
    AgentModeScrollDirection.Up -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    AgentModeScrollDirection.Down -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    AgentModeScrollDirection.Left -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    AgentModeScrollDirection.Right -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
}

/** How the touch is delivered; the model picks, and a failure is reported rather than retried. */
internal enum class AgentModeDelivery(val storageValue: String) {
    /** A real finger touch through the input pipeline: closest to what the user does. */
    Touch("touch"),

    /** The node's own click action: bypasses hit testing, but only clickable nodes answer it. */
    Action("action"),
    ;

    companion object {
        fun fromStorage(value: String?): AgentModeDelivery =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() } ?: Touch
    }
}

/** Default long-press duration, used only when the device reports no long-press timeout. */
internal const val AgentModeFallbackLongPressMillis = 700L

/**
 * Long-press duration for this device.
 *
 * Read from the platform instead of hard-coded because the timeout is a per-device configuration:
 * a press shorter than the device's own threshold is dispatched as an ordinary tap, which is the
 * failure mode a guessed constant produces on exactly the devices that differ from the guess.
 */
internal fun agentModeLongPressMillis(deviceTimeoutMillis: Int, requestedMillis: Int?): Long {
    if (requestedMillis != null && requestedMillis > 0) return requestedMillis.toLong().coerceIn(200L, 10_000L)
    val timeout = if (deviceTimeoutMillis > 0) deviceTimeoutMillis.toLong() else AgentModeFallbackLongPressMillis
    return (timeout + AgentModeLongPressMarginMillis).coerceIn(300L, 10_000L)
}

/** A press must outlast the device's own long-press timeout, not merely reach it. */
private const val AgentModeLongPressMarginMillis = 200L
