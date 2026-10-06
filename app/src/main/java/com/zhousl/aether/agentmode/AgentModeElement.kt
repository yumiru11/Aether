package com.zhousl.aether.agentmode

import kotlin.math.sqrt

/** Sentinel for [AgentModeElement.tapTargetId] when no clickable ancestor was found. */
internal const val AgentModeNoTapTarget = 0

/**
 * Screen bounds of one element, in display pixels, with the same edge semantics as
 * [android.graphics.Rect]: the right and bottom edges are exclusive.
 */
internal data class AgentModeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int): Boolean =
        x >= left && x < right && y >= top && y < bottom

    fun intersect(other: AgentModeBounds): AgentModeBounds = AgentModeBounds(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = minOf(right, other.right),
        bottom = minOf(bottom, other.bottom),
    )

    /** Shortest distance from a point to this rectangle, zero when the point is inside it. */
    fun distanceTo(x: Int, y: Int): Int {
        val dx = maxOf(left - x, 0, x - (right - 1))
        val dy = maxOf(top - y, 0, y - (bottom - 1))
        if (dx == 0 && dy == 0) return 0
        return sqrt((dx.toLong() * dx + dy.toLong() * dy).toDouble()).toInt()
    }
}

/**
 * Interaction signals read from one accessibility node. Kept as a value type so a re-read can be
 * compared against an earlier read without listing every field at each call site.
 */
internal data class AgentModeElementSignals(
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * One element of the accessibility tree as it was handed to the model.
 *
 * [id] is only meaningful for the observation that produced it. Element actions therefore re-read
 * the tree and match on [fingerprint] rather than trusting the id or the recorded bounds: the whole
 * point is to act on where an element is *now*, not where it was when the model looked.
 */
internal data class AgentModeElement(
    val id: Int,
    val windowIndex: Int,
    val className: String,
    val text: String,
    val description: String,
    /**
     * Resource name of the node, empty when the app does not expose one. Never rendered: it costs
     * context without being anything the model can act on. It is part of the fingerprint because an
     * icon button with no label would otherwise be indistinguishable from every other unlabelled
     * button of the same class on the same screen.
     */
    val viewId: String = "",
    val bounds: AgentModeBounds,
    val depth: Int,
    val signals: AgentModeElementSignals = AgentModeElementSignals(),
    val tapTargetId: Int = AgentModeNoTapTarget,
    val tapTargetBounds: AgentModeBounds? = null,
) {
    /** The label the model reads: visible text first, then the content description. */
    val label: String get() = text.ifBlank { description }

    /** Whether the element does anything the model can ask for. */
    val actionable: Boolean
        get() = signals.clickable ||
            signals.longClickable ||
            signals.editable ||
            signals.scrollable ||
            signals.checkable

    val fingerprint: AgentModeElementFingerprint get() = AgentModeElementFingerprint.of(this)
}

/**
 * What makes an element "the same element" across two reads of the tree.
 *
 * Bounds are deliberately excluded. The interesting case is an element that moved (a scrolled
 * list, an opened keyboard), and matching on bounds would report it as gone exactly when resolving
 * it matters most. Several identical matches are resolved by proximity instead (see
 * [resolveAgentModeTarget]).
 */
internal data class AgentModeElementFingerprint(
    val windowIndex: Int,
    val className: String,
    val label: String,
    val viewId: String,
) {
    companion object {
        fun of(element: AgentModeElement): AgentModeElementFingerprint =
            AgentModeElementFingerprint(
                windowIndex = element.windowIndex,
                className = element.className,
                label = element.label,
                viewId = element.viewId,
            )
    }
}

internal sealed interface AgentModeTargetResolution {
    /** The element is still on screen; its bounds come from the fresh read. */
    data class Resolved(val element: AgentModeElement) : AgentModeTargetResolution

    /** The id was never handed out for this display. */
    data object Unknown : AgentModeTargetResolution

    /** The element was visible when it was observed but is gone from the fresh read. */
    data class Gone(val label: String, val className: String) : AgentModeTargetResolution
}

/**
 * Re-locates an element the model pointed at by id.
 *
 * A recycled list row can leave several identical fingerprints behind, so the match closest to where
 * the element used to be wins. That is the only place the stale bounds are still used, and only to
 * break a tie.
 */
internal fun resolveAgentModeTarget(
    targetId: Int,
    previous: List<AgentModeElement>,
    current: List<AgentModeElement>,
): AgentModeTargetResolution {
    val stale = previous.firstOrNull { it.id == targetId } ?: return AgentModeTargetResolution.Unknown
    val matches = current.filter { it.fingerprint == stale.fingerprint }
    return when {
        matches.isEmpty() -> AgentModeTargetResolution.Gone(stale.label, stale.className)
        matches.size == 1 -> AgentModeTargetResolution.Resolved(matches.first())
        else -> AgentModeTargetResolution.Resolved(
            matches.minByOrNull {
                it.bounds.distanceTo(stale.bounds.centerX, stale.bounds.centerY)
            } ?: matches.first(),
        )
    }
}

/** A clickable ancestor may be at most this many times larger than the element inside it. */
private const val AgentModeTapTargetAreaRatio = 4L

/** A clickable ancestor covering more than half the screen is a container, not a target. */
private const val AgentModeTapTargetScreenAreaDivisor = 2L

internal sealed interface AgentModeTapPoint {
    data class Resolved(
        val x: Int,
        val y: Int,
        /** The element whose bounds produced the point; differs from the target when an ancestor was used. */
        val elementId: Int,
        val note: String = "",
    ) : AgentModeTapPoint

    data class Unresolved(val reason: String) : AgentModeTapPoint
}

/**
 * Turns an element into the pixel the touch should land on.
 *
 * The point is always the centre of the part of the target that is actually on screen, so an element
 * half scrolled off the bottom cannot produce a coordinate outside the display. When the element
 * itself is not clickable the nearest clickable ancestor is used, but only if it is a plausible
 * target rather than a whole-screen container.
 */
internal fun resolveAgentModeTapPoint(
    element: AgentModeElement,
    screen: AgentModeBounds,
): AgentModeTapPoint {
    val visible = element.bounds.intersect(screen)
    if (visible.isEmpty) return AgentModeTapPoint.Unresolved("element_is_offscreen")

    if (element.signals.clickable) {
        return AgentModeTapPoint.Resolved(
            x = visible.centerX,
            y = visible.centerY,
            elementId = element.id,
            note = if (element.signals.enabled) "" else "element_is_disabled",
        )
    }

    val ancestorBounds = element.tapTargetBounds
    if (element.tapTargetId != AgentModeNoTapTarget && ancestorBounds != null) {
        val ancestorVisible = ancestorBounds.intersect(screen)
        val withinElementRatio =
            ancestorBounds.area <= element.bounds.area.coerceAtLeast(1L) * AgentModeTapTargetAreaRatio
        val withinScreen =
            ancestorBounds.area * AgentModeTapTargetScreenAreaDivisor <= screen.area
        if (!ancestorVisible.isEmpty && withinElementRatio && withinScreen) {
            return AgentModeTapPoint.Resolved(
                x = ancestorVisible.centerX,
                y = ancestorVisible.centerY,
                elementId = element.tapTargetId,
            )
        }
        return AgentModeTapPoint.Resolved(
            x = visible.centerX,
            y = visible.centerY,
            elementId = element.id,
            note = "clickable_ancestor_too_large",
        )
    }

    return AgentModeTapPoint.Resolved(
        x = visible.centerX,
        y = visible.centerY,
        elementId = element.id,
        note = "no_clickable_ancestor",
    )
}

/**
 * The element a raw coordinate lands on.
 *
 * Windows are enumerated topmost-first and nodes depth-first, so the topmost leaf under a point is
 * the one with the smallest window index, the greatest depth and, among equals, the smallest area.
 */
internal fun hitTestAgentModeElement(
    elements: List<AgentModeElement>,
    x: Int,
    y: Int,
): AgentModeElement? =
    elements
        .filter { it.bounds.contains(x, y) }
        .maxWithOrNull(
            compareBy<AgentModeElement> { -it.windowIndex }
                .thenBy { it.depth }
                .thenByDescending { it.bounds.area },
        )

/** Closest element the model could have meant, used to explain a coordinate that hit nothing. */
internal fun nearestActionableAgentModeElement(
    elements: List<AgentModeElement>,
    x: Int,
    y: Int,
): AgentModeElement? =
    elements
        .filter { it.actionable && !it.bounds.isEmpty }
        .minByOrNull { it.bounds.distanceTo(x, y) }

/** What changed on screen between two observations. */
internal data class AgentModeObservationDelta(
    val added: List<AgentModeElement>,
    val removed: List<AgentModeElement>,
    val changed: List<AgentModeElement>,
) {
    val total: Int get() = added.size + removed.size + changed.size
    val isEmpty: Boolean get() = total == 0
}

/**
 * Compares two observations so a result can say *what* changed instead of only *that* something did.
 * Removal is matched on the previous read, so an element that moved is reported as changed rather
 * than as one removal plus one addition.
 */
internal fun diffAgentModeElements(
    previous: List<AgentModeElement>,
    current: List<AgentModeElement>,
): AgentModeObservationDelta {
    val previousByFingerprint = previous.groupBy { it.fingerprint }
    val currentByFingerprint = current.groupBy { it.fingerprint }
    val added = current.filter { previousByFingerprint[it.fingerprint].isNullOrEmpty() }
    val removed = previous.filter { currentByFingerprint[it.fingerprint].isNullOrEmpty() }
    val changed = current.filter { element ->
        val before = previousByFingerprint[element.fingerprint]?.firstOrNull() ?: return@filter false
        before.bounds != element.bounds || before.signals != element.signals
    }
    return AgentModeObservationDelta(added = added, removed = removed, changed = changed)
}
