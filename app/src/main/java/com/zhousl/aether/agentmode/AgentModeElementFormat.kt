package com.zhousl.aether.agentmode

/** How many elements an observation returns before it starts reporting omissions. */
internal const val AgentModeDefaultMaxElements = 60
internal const val AgentModeMaxElementsLimit = 400

/**
 * Code point budget for the rendered element rows. The rows are the only part of an Agent Mode result
 * that grows with screen density, so this is the number that decides how much context one observation
 * costs. Sized for a dense screen at roughly 40 characters per element.
 */
internal const val AgentModeDefaultElementBudget = 8_000
internal const val AgentModeMaxElementBudget = 20_000

/**
 * Longest label kept per element. Labels are cut head-and-tail rather than head-only because the part
 * that identifies a control often lives at the end of it (an order id, a URL query string, a pickup
 * code), and a head-only cut silently removes exactly that.
 */
private const val AgentModeMaxLabelChars = 160
private const val AgentModeLabelTailChars = 32

/** Beyond this many changed elements a delta stops being a summary and starts being a screen dump. */
internal const val AgentModeDeltaLimit = 8

private const val AgentModeElementLegend =
    "# rows are in screen order; c=click l=long-click t=edit s=scroll i=focusable f=focused" +
        " k=checked u=checkable x=selected d=disabled @=content-description ->N=ancestor-is-the-tap-target"

internal data class AgentModeElementFormatOptions(
    val maxElements: Int = AgentModeDefaultMaxElements,
    val budget: Int = AgentModeDefaultElementBudget,
    val interactiveOnly: Boolean = true,
    val query: String = "",
    val region: AgentModeBounds? = null,
)

internal data class AgentModeElementRenderResult(
    val text: String,
    /** Elements left after filtering, before the budget and the element cap are applied. */
    val total: Int,
    val shown: Int,
    val omitted: Int,
)

/**
 * Renders an observation as text.
 *
 * Rows keep screen order so the model can line them up with a screenshot, and so truncation is
 * explainable: whatever is cut is a contiguous band at the bottom of the screen, which the reported
 * cutoff and a region observation can both address. Ranking by usefulness instead would scatter the
 * omitted elements across the screen and make the result impossible to resume from.
 */
internal fun renderAgentModeElements(
    statusLine: String,
    elements: List<AgentModeElement>,
    options: AgentModeElementFormatOptions = AgentModeElementFormatOptions(),
): AgentModeElementRenderResult {
    val filtered = filterAgentModeElements(elements, options)
    val capped = filtered.take(options.maxElements.coerceIn(1, AgentModeMaxElementsLimit))
    val budget = options.budget.coerceIn(1, AgentModeMaxElementBudget)

    val rows = mutableListOf<String>()
    var used = statusLine.length + AgentModeElementLegend.length + 2
    var firstOmittedTop: Int? = null
    for (element in capped) {
        val row = agentModeElementRow(element)
        if (used + row.length + 1 > budget) {
            firstOmittedTop = element.bounds.top
            break
        }
        rows += row
        used += row.length + 1
    }

    val rendered = rows.size
    val omitted = filtered.size - rendered
    val text = buildString {
        append(statusLine)
        append('\n')
        append(AgentModeElementLegend)
        if (omitted > 0) {
            append(" omitted=")
            append(omitted)
            firstOmittedTop?.let { top ->
                append(" from_y=")
                append(top)
                append(" (narrow with region or query)")
            }
        }
        rows.forEach { row ->
            append('\n')
            append(row)
        }
    }
    return AgentModeElementRenderResult(
        text = text,
        total = filtered.size,
        shown = rendered,
        omitted = omitted,
    )
}

/**
 * Drops what the model cannot use: elements with neither an action nor a label, and the second copy
 * of two nodes that occupy identical bounds and carry identical labels: Android nests containers at
 * byte-identical rectangles, and those duplicates used to cost context without adding information.
 */
internal fun filterAgentModeElements(
    elements: List<AgentModeElement>,
    options: AgentModeElementFormatOptions = AgentModeElementFormatOptions(),
): List<AgentModeElement> {
    val query = options.query.trim().lowercase()
    val region = options.region
    val seen = mutableSetOf<String>()
    val result = mutableListOf<AgentModeElement>()
    for (element in elements) {
        if (element.bounds.isEmpty && element.label.isBlank()) continue
        if (region != null && element.bounds.intersect(region).isEmpty) continue
        if (options.interactiveOnly && !element.actionable && element.label.isBlank()) continue
        if (query.isNotEmpty()) {
            val haystack = element.label.lowercase() +
                ' ' + element.className.lowercase() +
                ' ' + element.id
            if (!haystack.contains(query)) continue
        }
        val key = element.bounds.left.toString() + ',' +
            element.bounds.top + ',' +
            element.bounds.right + ',' +
            element.bounds.bottom + '|' +
            element.className + '|' +
            element.label
        if (!seen.add(key)) continue
        result += element
    }
    return result.sortedWith(
        compareBy<AgentModeElement> { it.windowIndex }
            .thenBy { it.bounds.top }
            .thenBy { it.bounds.left }
            .thenBy { it.bounds.area },
    )
}

/** Column order: id, class, flags, bounds, then every free-text field last so rows stay splittable. */
internal fun agentModeElementRow(element: AgentModeElement): String = buildString {
    append(element.id)
    append(' ')
    append(shortClassName(element.className))
    append(' ')
    append(elementFlags(element))
    append(' ')
    append(element.bounds.left).append(',')
    append(element.bounds.top).append(',')
    append(element.bounds.right).append(',')
    append(element.bounds.bottom)
    val label = element.label
    if (label.isNotBlank()) {
        append(' ')
        if (element.text.isBlank()) append('@')
        append(shortenLabel(label))
    }
    if (element.tapTargetId != AgentModeNoTapTarget && element.tapTargetId != element.id) {
        append(" ->")
        append(element.tapTargetId)
    }
}

internal fun elementFlags(element: AgentModeElement): String = buildString {
    val signals = element.signals
    if (signals.clickable) append('c')
    if (signals.longClickable) append('l')
    if (signals.editable) append('t')
    if (signals.scrollable) append('s')
    if (signals.focusable) append('i')
    if (signals.focused) append('f')
    if (signals.checkable) append(if (signals.checked) 'k' else 'u')
    if (signals.selected) append('x')
    if (!signals.enabled) append('d')
    if (isEmpty()) append('-')
}

/** `android.widget.TextView` reads as `TextView`; the package prefix is noise in every row. */
internal fun shortClassName(className: String): String {
    if (className.isBlank()) return "View"
    val trimmed = className
        .removePrefix("android.widget.")
        .removePrefix("android.view.")
        .removePrefix("android.webkit.")
        .removePrefix("android.app.")
    return trimmed.substringAfterLast('.')
}

internal fun shortenLabel(label: String): String {
    val flattened = label.replace('\n', ' ').replace('\r', ' ').trim()
    if (flattened.length <= AgentModeMaxLabelChars) return flattened
    val head = AgentModeMaxLabelChars - AgentModeLabelTailChars
    val dropped = flattened.length - AgentModeMaxLabelChars
    return flattened.take(head) + "...[cut " + dropped + "]..." + flattened.takeLast(AgentModeLabelTailChars)
}

/**
 * One short line per changed element, used so an action result can say what changed without paying
 * for a whole observation. Returns null when the change is larger than a summary can honestly
 * describe; the caller then reports that the screen changed and leaves the detail to an observation.
 */
internal fun renderAgentModeElementDelta(
    delta: AgentModeObservationDelta,
    limit: Int = AgentModeDeltaLimit,
): String? {
    if (delta.isEmpty) return null
    if (delta.total > limit) return null
    val lines = mutableListOf<String>()
    fun add(marker: Char, elements: List<AgentModeElement>) {
        elements.forEach { element ->
            lines += buildString {
                append(marker)
                append(element.id)
                append(' ')
                append(shortClassName(element.className))
                if (element.label.isNotBlank()) {
                    append(' ')
                    append(shortenLabel(element.label))
                }
            }
        }
    }
    add('+', delta.added)
    add('-', delta.removed)
    add('~', delta.changed)
    return lines.joinToString("\n")
}
