package com.zhousl.aether.agentmode

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Binder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.getSystemService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/** One accessibility read: the elements the model sees, plus the nodes an action runs on. */
internal data class AgentModeTreeRead(
    val elements: List<AgentModeElement>,
    val nodes: Map<Int, AccessibilityNodeInfo>,
    val windowCount: Int,
    /** Nodes visited by this read, and whether the safety cap stopped it before the tree ran out. */
    val walkedNodes: Int = 0,
    val walkCapped: Boolean = false,
)

/**
 * What the model was last shown for one display. Kept so an action can report what changed relative
 * to what the model actually read, rather than relative to a read it never saw.
 */
internal data class AgentModeObservation(
    val elements: List<AgentModeElement>,
    val windowCount: Int,
)

/** The settle verdict together with the tree it settled on. */
internal data class AgentModeSettleOutcome(
    val report: AgentModeSettleReport,
    val read: AgentModeTreeRead,
)

/**
 * Reads the accessibility tree of the Agent Mode display and turns it into the model-facing element
 * list.
 *
 * The reader owns the process's single [UiAutomation] connection. Two things about that connection
 * are load-bearing and easy to get wrong:
 *
 * 1. It is created and connected on the caller's binder thread. `UiAutomationConnection` registers the
 *    automation for `Binder.getCallingUserHandle()`, so this is what makes an Agent Mode app running
 *    on a secondary Android user observable at all; connecting from a thread of our own would silently
 *    register for user 0 and return an empty screen forever.
 * 2. It connects with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Without that flag the platform
 *    stops every accessibility service the user has enabled (TalkBack, password managers, automation
 *    apps) for as long as the connection is held. Agent Mode is not a screen reader.
 */
internal class AgentModeElementReader(private val context: Context) {
    private val displayManager: DisplayManager by lazy { context.getSystemService<DisplayManager>()!! }
    private val lock = ReentrantLock()
    private val boundsRect = Rect()
    private val lastObservations = mutableMapOf<Int, AgentModeObservation>()

    private var automation: UiAutomation? = null
    private var automationConnection: Any? = null
    private var automationDisplayId: Int? = null
    private var capability: AgentModeElementCapability? = null

    /** Nodes visited by the current read, and whether the safety cap stopped it early. */
    private var walkedNodes: Int = 0
    private var walkCapped: Boolean = false

    fun capabilitiesJson(): String = lock.withLock {
        val current = capability ?: probeWithoutConnecting()
        current.toJson().toString()
    }

    /** Reads the tree and returns the rendered observation, storing it for the next element action. */
    fun observe(displayId: Int, optionsJson: String): String = lock.withLock {
        val options = runCatching { JSONObject(optionsJson) }.getOrNull() ?: JSONObject()
        val connected = ensureConnected(displayId)
        if (!connected.isAvailable) return connected.failureJson("observe")
        val screen = screenBounds(displayId) ?: return missingDisplayJson(displayId, "observe")

        prepareRead()
        var read = readTree(displayId, connected, screen)
        var passes = 1
        if (needsWakePass(read)) {
            // A WebView renderer builds its accessibility tree asynchronously after being queried, and an
            // app under a transition can report a tree with only its own chrome. Reading once more after
            // a short pause is the difference between an empty screen and the real one.
            //
            // The pause is only paid when there is evidence of that state - an empty tree, or one that
            // contains a WebView - because a genuinely sparse screen (a dialog with three controls)
            // looks the same at first glance and would otherwise pay it on every single read.
            SystemClock.sleep(AgentModeObservationWakeDelayMillis)
            val second = readTree(displayId, connected, screen)
            passes = 2
            if (second.elements.size > read.elements.size) read = second
        }
        val rendered = renderRead(
            displayId = displayId,
            read = read,
            passes = passes,
            options = AgentModeElementFormatOptions(
                maxElements = options.optInt("max_elements", AgentModeDefaultMaxElements),
                interactiveOnly = options.optBoolean("interactive_only", true),
                query = options.optString("query"),
                region = parseRegion(options.optString("region")),
            ),
        )
        return JSONObject().apply {
            put("ok", true)
            put("action", "observe")
            put("display_id", displayId)
            put("width", screen.width)
            put("height", screen.height)
            put("windows", read.windowCount)
            put("total", rendered.total)
            put("shown", rendered.shown)
            put("omitted", rendered.omitted)
            put("reads", passes)
            put("nodes_walked", read.walkedNodes)
            if (read.walkCapped) put("walk_capped", true)
            put("reader_mode", connected.readerMode.storageValue)
            put("caller_user", connected.callerUser)
            put("service_user", connected.serviceUser)
            if (read.elements.isEmpty()) {
                put("no_windows", read.windowCount == 0)
            }
            put("stdout", rendered.text)
        }.toString()
    }

    /** Waits for the display to stop changing and reports the signal the decision was built from. */
    fun settle(displayId: Int, optionsJson: String): String = lock.withLock {
        val options = runCatching { JSONObject(optionsJson) }.getOrNull() ?: JSONObject()
        val connected = ensureConnected(displayId)
        if (!connected.isAvailable) return connected.failureJson("settle")
        val outcome = settleCurrent(
            displayId = displayId,
            connected = connected,
            timeoutMillis = options.optLong("timeout_ms", AgentModeSettleTimeoutMillis),
        ) ?: return missingDisplayJson(displayId, "settle")
        JSONObject().apply {
            put("ok", true)
            put("action", "settle")
            put("settled", outcome.report.settled)
            put("elapsed_ms", outcome.report.elapsedMillis)
            put("samples", outcome.report.samples)
            put("signal", outcome.report.signal)
        }.toString()
    }

    /**
     * Waits for the display to stop changing and hands back the read it stopped at, so a caller that
     * needs both does not pay for a second walk over the tree. Null when the channel is unavailable.
     */
    fun settleRead(displayId: Int, timeoutMillis: Long): AgentModeSettleOutcome? = lock.withLock {
        val connected = capability ?: return null
        if (!connected.isAvailable) return null
        settleCurrent(displayId, connected, timeoutMillis)
    }

    private fun settleCurrent(
        displayId: Int,
        connected: AgentModeElementCapability,
        timeoutMillis: Long,
    ): AgentModeSettleOutcome? {
        val screen = screenBounds(displayId) ?: return null
        val boundedTimeout = timeoutMillis.coerceIn(0L, AgentModeMaxSettleTimeoutMillis)
        val startedAt = SystemClock.uptimeMillis()
        val tracker = AgentModeSettleTracker()
        prepareRead()
        var read = readTree(displayId, connected, screen)
        while (true) {
            tracker.record(agentModeScreenSignature(read.windowCount, read.elements))
            if (tracker.isSettled) break
            if (SystemClock.uptimeMillis() - startedAt >= boundedTimeout) break
            SystemClock.sleep(AgentModeSettleSampleIntervalMillis)
            read = readTree(displayId, connected, screen)
        }
        return AgentModeSettleOutcome(
            report = AgentModeSettleReport(
                settled = tracker.isSettled,
                elapsedMillis = SystemClock.uptimeMillis() - startedAt,
                samples = tracker.samples,
                signal = "elements",
            ),
            read = read,
        )
    }

    /**
     * Reads the tree for an element action. The service uses the returned nodes to run the action and
     * the returned elements to work out what changed afterwards.
     */
    fun read(displayId: Int): AgentModeTreeRead = lock.withLock {
        val connected = ensureConnected(displayId)
        if (!connected.isAvailable) error(connected.availability.reason)
        val screen = screenBounds(displayId) ?: error("Display " + displayId + " is not available.")
        prepareRead()
        readTree(displayId, connected, screen)
    }

    /** The observation the model last saw on [displayId], used as the "before" side of a change report. */
    fun observedObservation(displayId: Int): AgentModeObservation? = lock.withLock {
        lastObservations[displayId]
    }

    fun recordObservation(displayId: Int, observation: AgentModeObservation) = lock.withLock {
        lastObservations[displayId] = observation
    }

    /** Connects if needed and reports whether element reads are currently possible. */
    fun capability(displayId: Int): AgentModeElementCapability = lock.withLock {
        ensureConnected(displayId)
    }

    /**
     * Renders a read the caller already has and stores it as the observation the next element action
     * resolves against. Exposed so an action that cannot find its target can answer with the fresh
     * list in the same round trip instead of making the model ask for it.
     */
    fun renderRead(
        displayId: Int,
        read: AgentModeTreeRead,
        passes: Int = 1,
        options: AgentModeElementFormatOptions = AgentModeElementFormatOptions(),
    ): AgentModeElementRenderResult {
        val screen = screenBounds(displayId)
        val mode = automationReaderMode().storageValue
        lastObservations[displayId] = AgentModeObservation(read.elements, read.windowCount)
        val statusLine = buildString {
            append("# agent-ui ")
            append(displayId)
            append(' ')
            append(screen?.width ?: 0)
            append('x')
            append(screen?.height ?: 0)
            append(" win=")
            append(read.windowCount)
            append(" nodes=")
            append(read.elements.size)
            append(" reads=")
            append(passes)
            append(" mode=")
            append(mode)
        }
        return renderAgentModeElements(statusLine = statusLine, elements = read.elements, options = options)
    }

    /** Which window path this connection uses, for the status line; "none" before connecting. */
    private fun automationReaderMode(): AgentModeElementReaderMode =
        capability?.readerMode ?: AgentModeElementReaderMode.None

    /**
     * Tears the connection down. Called from the caller's binder thread, which is also the identity
     * the connection was registered under.
     */
    fun detach() = lock.withLock {
        val automation = this.automation
        val connection = this.automationConnection
        this.automation = null
        this.automationConnection = null
        this.automationDisplayId = null
        this.capability = null
        lastObservations.clear()
        if (automation != null) disconnectAutomation(automation, connection)
    }

    private fun ensureConnected(displayId: Int): AgentModeElementCapability {
        val current = capability
        if (current != null && current.isAvailable && automation != null && automationDisplayId == displayId) {
            return current
        }
        if (automation != null) detach()
        val connected = connect(displayId)
        capability = connected
        return connected
    }

    private fun connect(displayId: Int): AgentModeElementCapability {
        val sdkAvailability = agentModeElementAvailabilityForSdk(Build.VERSION.SDK_INT)
        if (!sdkAvailability.isAvailable) {
            return AgentModeElementCapability.unavailable(sdkAvailability)
        }
        return runCatching { connectInternal(displayId) }.getOrElse { throwable ->
            AgentModeElementCapability.unavailable(
                availability = agentModeElementAvailabilityForFailure(throwable),
                detail = throwable.message ?: throwable.javaClass.simpleName,
            )
        }
    }

    private fun connectInternal(displayId: Int): AgentModeElementCapability {
        val connectionClass = Class.forName("android.app.UiAutomationConnection")
        val connectionInterface = Class.forName("android.app.IUiAutomationConnection")
        val connection = connectionClass.getConstructor().newInstance()

        val display = displayManager.getDisplay(displayId)
            ?: error("Display " + displayId + " is not available for element observation.")
        val displayContext = context.createDisplayContext(display)

        // Newer releases can bind the automation to one display through a display-scoped context; that
        // makes the automation's own window list ours. Older ones only expose the per-display list, so
        // the same result is reached by enumerating every display and filtering.
        val displayScopedConstructor = runCatching {
            UiAutomation::class.java.getConstructor(Context::class.java, connectionInterface)
        }.getOrNull()
        val automation = if (displayScopedConstructor != null) {
            displayScopedConstructor.newInstance(displayContext, connection) as UiAutomation
        } else {
            UiAutomation::class.java
                .getConstructor(Looper::class.java, connectionInterface)
                .newInstance(Looper.getMainLooper(), connection) as UiAutomation
        }

        connectAutomation(automation)

        // A window list is only populated for a connection that asked for interactive windows, and the
        // service info the connection registers with does not set that flag. This is the same step
        // uiautomator's own dump command performs.
        val serviceInfo = automation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo

        restoreConnectionOwner(connection, connectionClass)

        this.automation = automation
        this.automationConnection = connection
        this.automationDisplayId = displayId
        return AgentModeElementCapability(
            availability = AgentModeElementAvailability.Available,
            readerMode = if (displayScopedConstructor != null) {
                AgentModeElementReaderMode.DisplayScoped
            } else {
                AgentModeElementReaderMode.AllDisplays
            },
            callerUser = Binder.getCallingUid() / AgentModeServiceProtocol.PerUserRange,
            serviceUser = Process.myUid() / AgentModeServiceProtocol.PerUserRange,
        )
    }

    /**
     * Connects without suppressing what the user already relies on.
     *
     * `UiAutomation.suppressAccessibilityServices` is the default, and the platform honours it: with
     * the flag unset, every enabled accessibility service stops for as long as this connection lives.
     */
    private fun connectAutomation(automation: UiAutomation) {
        val flags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val connectWithFlags = runCatching {
            UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (connectWithFlags != null) {
            connectWithFlags.invoke(automation, flags)
        } else {
            UiAutomation::class.java.getMethod("connect").invoke(automation)
        }
    }

    /**
     * Puts the connection owner back to this process.
     *
     * Connecting on the caller's binder thread is what registers the automation for the caller's
     * Android user, and as a side effect the connection records that caller as its owner. Every later
     * call comes from this process, and the owner check on disconnect compares against
     * `Binder.getCallingUid()`, so the value is restored to what it would have been had the connection
     * been made from a thread of our own.
     */
    private fun restoreConnectionOwner(connection: Any, connectionClass: Class<*>) {
        runCatching {
            connectionClass.getDeclaredField("mOwningUid").apply { isAccessible = true }
                .setInt(connection, Process.myUid())
        }
    }

    private fun disconnectAutomation(automation: UiAutomation, connection: Any?) {
        val detached = runCatching {
            UiAutomation::class.java.getMethod("disconnect").invoke(automation)
        }
        if (detached.isSuccess) return
        if (connection != null) {
            runCatching { connection.javaClass.getMethod("disconnect").invoke(connection) }
        }
    }

    private fun readTree(
        displayId: Int,
        connected: AgentModeElementCapability,
        screen: AgentModeBounds,
    ): AgentModeTreeRead {
        val automation = this.automation
            ?: error("Element observation is not connected.")
        val windows = windowsForDisplay(automation, displayId, connected.readerMode)
        walkedNodes = 0
        walkCapped = false
        val elements = mutableListOf<AgentModeElement>()
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        windows.forEachIndexed { windowIndex, window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEachIndexed
            collectNode(
                node = root,
                windowIndex = windowIndex,
                depth = 0,
                inheritedTapTarget = null,
                screen = screen,
                elements = elements,
                nodes = nodes,
            )
        }
        return AgentModeTreeRead(
            elements = elements,
            nodes = nodes,
            windowCount = windows.size,
            walkedNodes = walkedNodes,
            walkCapped = walkCapped,
        )
    }

    private fun windowsForDisplay(
        automation: UiAutomation,
        displayId: Int,
        mode: AgentModeElementReaderMode,
    ): List<AccessibilityWindowInfo> = when (mode) {
        AgentModeElementReaderMode.DisplayScoped -> automation.windows.orEmpty()
        AgentModeElementReaderMode.AllDisplays -> windowsOnAllDisplays(automation)[displayId].orEmpty()
        AgentModeElementReaderMode.None -> emptyList()
    }

    private fun windowsOnAllDisplays(
        automation: UiAutomation,
    ): SparseArray<List<AccessibilityWindowInfo>> {
        // Reached only in AllDisplays mode, which the SDK gate above only allows from Android 11 -
        // the release that first exposed a window list keyed by display id.
        if (Build.VERSION.SDK_INT < AgentModeMultiDisplayAccessibilityApi) {
            error("Per-display window enumeration requires Android 11 or newer.")
        }
        return automation.windowsOnAllDisplays
    }

    /**
     * Invalidates the accessibility cache once before a user-facing read.
     *
     * Deliberately not called per settle sample: samples are tens of milliseconds apart while the cache
     * describes a screen that cannot meaningfully have moved in that time, and the invalidation itself
     * costs a call into the accessibility framework. A cache that outlives the screen it describes is
     * how a tap ends up aimed at where an element used to be, so it is dropped at every entry point.
     */
    private fun prepareRead() {
        val automation = this.automation ?: return
        invalidateAccessibilityCache(automation)
    }

    private fun invalidateAccessibilityCache(automation: UiAutomation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { automation.clearCache() }
            return
        }
        runCatching {
            val clientClass = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
            val client = clientClass.getMethod("getInstance").invoke(null)
            clientClass.methods
                .firstOrNull { it.name == "clearCache" && it.parameterCount == 0 }
                ?.invoke(client)
        }
    }

    /**
     * Walks one node and its children depth-first.
     *
     * An element id is only handed out to a node that ends up in the list, so the ids the model reads
     * are dense and stay small. [inheritedTapTarget] carries the nearest clickable ancestor downwards,
     * which is what lets a plain label be tappable without a second pass over the tree.
     */
    private fun collectNode(
        node: AccessibilityNodeInfo,
        windowIndex: Int,
        depth: Int,
        inheritedTapTarget: Pair<Int, AgentModeBounds>?,
        screen: AgentModeBounds,
        elements: MutableList<AgentModeElement>,
        nodes: MutableMap<Int, AccessibilityNodeInfo>,
    ) {
        if (walkedNodes >= AgentModeMaxWalkedNodes) {
            walkCapped = true
            return
        }
        walkedNodes++

        val bounds = readBounds(node)
        // A node with laid-out bounds that lie entirely off screen cannot contain anything on screen -
        // a long list keeps hundreds of such rows - so the subtree is skipped. Empty bounds are walked
        // anyway, because they say nothing about where the children are.
        if (!bounds.isEmpty && bounds.intersect(screen).isEmpty) return

        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
            .ifBlank { node.hintText?.toString().orEmpty() }
        val visibleOnScreen = !bounds.intersect(screen).isEmpty
        val hasSomethingToSay = !bounds.isEmpty || text.isNotBlank() || description.isNotBlank()
        val emits = visibleOnScreen && hasSomethingToSay
        val elementId = if (emits) elements.size + 1 else AgentModeNoTapTarget
        val clickable = node.isClickable
        val tapTarget = when {
            clickable && emits -> elementId to bounds
            clickable -> null
            else -> inheritedTapTarget
        }

        if (emits) {
            elements += AgentModeElement(
                id = elementId,
                windowIndex = windowIndex,
                className = node.className?.toString().orEmpty(),
                text = text,
                description = description,
                viewId = node.viewIdResourceName.orEmpty(),
                bounds = bounds,
                depth = depth,
                signals = AgentModeElementSignals(
                    clickable = clickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    focusable = node.isFocusable,
                    focused = node.isFocused,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    selected = node.isSelected,
                    enabled = node.isEnabled,
                ),
                tapTargetId = tapTarget?.first ?: AgentModeNoTapTarget,
                tapTargetBounds = tapTarget?.second,
            )
            nodes[elementId] = node
        }

        val childCount = node.childCount
        for (index in 0 until childCount) {
            val child = node.getChild(index) ?: continue
            collectNode(
                node = child,
                windowIndex = windowIndex,
                depth = depth + 1,
                inheritedTapTarget = tapTarget,
                screen = screen,
                elements = elements,
                nodes = nodes,
            )
        }
    }

    private fun readBounds(node: AccessibilityNodeInfo): AgentModeBounds {
        boundsRect.setEmpty()
        runCatching { node.getBoundsInScreen(boundsRect) }
        return AgentModeBounds(boundsRect.left, boundsRect.top, boundsRect.right, boundsRect.bottom)
    }

    private fun screenBounds(displayId: Int): AgentModeBounds? {
        val display = displayManager.getDisplay(displayId) ?: return null
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val width = (display.mode?.physicalWidth ?: size.x).takeIf { it > 0 } ?: return null
        val height = (display.mode?.physicalHeight ?: size.y).takeIf { it > 0 } ?: return null
        return AgentModeBounds(0, 0, width, height)
    }

    private fun parseRegion(rawValue: String): AgentModeBounds? {
        val parts = rawValue.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return null
        val bounds = AgentModeBounds(parts[0], parts[1], parts[2], parts[3])
        return bounds.takeIf { !it.isEmpty }
    }

    private fun probeWithoutConnecting(): AgentModeElementCapability {
        val availability = agentModeElementAvailabilityForSdk(Build.VERSION.SDK_INT)
        return if (availability.isAvailable) {
            AgentModeElementCapability(
                availability = availability,
                readerMode = AgentModeElementReaderMode.None,
                callerUser = Binder.getCallingUid() / AgentModeServiceProtocol.PerUserRange,
                serviceUser = Process.myUid() / AgentModeServiceProtocol.PerUserRange,
            )
        } else {
            AgentModeElementCapability.unavailable(availability)
        }
    }
}

/**
 * Whether a read looks like a tree that has not been populated yet: either it came back empty, or what
 * it did return includes a WebView, whose renderer side is known to appear a moment after the query.
 */
private fun needsWakePass(read: AgentModeTreeRead): Boolean =
    read.elements.isEmpty() || read.elements.any { it.className.contains(AgentModeWebViewClassName) }

private const val AgentModeWebViewClassName = "WebView"

/**
 * Hard cap on nodes visited by one read. Reads happen several times per action, so a pathological
 * hierarchy must not be able to turn one action into seconds of walking; a capped read says so rather
 * than pretending it saw everything.
 */
private const val AgentModeMaxWalkedNodes = 3_000

/** Pause before the wake-up read; long enough for a WebView renderer to answer, short enough to hide. */
private const val AgentModeObservationWakeDelayMillis = 250L

/** Upper bound on a caller-supplied settle timeout, so a batch step cannot wait forever. */
internal const val AgentModeMaxSettleTimeoutMillis = 20_000L

/** Serialises a capability into the shape every entry point reports it in. */
internal fun AgentModeElementCapability.toJson(): JSONObject = JSONObject().apply {
    put("ok", isAvailable)
    put("available", isAvailable)
    put("reason", availability.storageValue)
    put("reader_mode", readerMode.storageValue)
    put("caller_user", callerUser)
    put("service_user", serviceUser)
    put("detail", detail.ifBlank { availability.reason })
}

/** The answer to a request the element channel cannot serve. Never silently a screenshot. */
internal fun AgentModeElementCapability.failureJson(action: String): String = JSONObject().apply {
    put("ok", false)
    put("action", action)
    put("elements_unavailable", availability.storageValue)
    put("reader_mode", readerMode.storageValue)
    put("caller_user", callerUser)
    put("service_user", serviceUser)
    put("errmsg", detail.ifBlank { availability.reason })
    put("stdout", "")
}.toString()

internal fun missingDisplayJson(displayId: Int, action: String): String = JSONObject().apply {
    put("ok", false)
    put("action", action)
    put("display_id", displayId)
    put("errmsg", "Display " + displayId + " is not available.")
    put("stdout", "")
}.toString()
