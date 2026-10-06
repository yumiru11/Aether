package com.zhousl.aether.agentmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeElementTest {
    private fun element(
        id: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        className: String = "android.widget.TextView",
        text: String = "",
        description: String = "",
        windowIndex: Int = 0,
        depth: Int = 0,
        signals: AgentModeElementSignals = AgentModeElementSignals(),
        tapTargetId: Int = AgentModeNoTapTarget,
        tapTargetBounds: AgentModeBounds? = null,
    ): AgentModeElement = AgentModeElement(
        id = id,
        windowIndex = windowIndex,
        className = className,
        text = text,
        description = description,
        bounds = AgentModeBounds(left, top, right, bottom),
        depth = depth,
        signals = signals,
        tapTargetId = tapTargetId,
        tapTargetBounds = tapTargetBounds,
    )

    private val screen = AgentModeBounds(0, 0, 1080, 2400)

    @Test
    fun boundsUseExclusiveRightAndBottomEdges() {
        val bounds = AgentModeBounds(10, 20, 110, 220)
        assertTrue(bounds.contains(10, 20))
        assertTrue(bounds.contains(109, 219))
        assertFalse(bounds.contains(110, 219))
        assertFalse(bounds.contains(109, 220))
    }

    @Test
    fun boundsIntersectionAndDistanceAreExact() {
        val bounds = AgentModeBounds(100, 100, 200, 200)
        assertEquals(AgentModeBounds(100, 100, 150, 200), bounds.intersect(AgentModeBounds(0, 0, 150, 400)))
        assertTrue(bounds.intersect(AgentModeBounds(300, 300, 400, 400)).isEmpty)
        assertEquals(0, bounds.distanceTo(150, 150))
        // The right and bottom edges are exclusive, so the nearest pixel of a rect ending at 200 is 199.
        assertEquals(51, bounds.distanceTo(250, 150))
        assertEquals(50, bounds.distanceTo(150, 50))
    }

    @Test
    fun labelPrefersVisibleTextOverContentDescription() {
        assertEquals("登录", element(1, 0, 0, 10, 10, text = "登录", description = "按钮").label)
        assertEquals("按钮", element(1, 0, 0, 10, 10, description = "按钮").label)
    }

    @Test
    fun tapPointUsesTheVisibleCentreOfAPartlyOffscreenElement() {
        val target = element(7, 0, 2300, 200, 2500, signals = AgentModeElementSignals(clickable = true))
        val point = resolveAgentModeTapPoint(target, screen)
        assertTrue(point is AgentModeTapPoint.Resolved)
        point as AgentModeTapPoint.Resolved
        assertEquals(100, point.x)
        assertEquals(2350, point.y)
        assertEquals(7, point.elementId)
    }

    @Test
    fun tapPointFallsBackToTheClickableAncestorForALabel() {
        val target = element(
            id = 9,
            left = 40,
            top = 100,
            right = 400,
            bottom = 300,
            tapTargetId = 4,
            tapTargetBounds = AgentModeBounds(20, 80, 1060, 320),
        )
        val point = resolveAgentModeTapPoint(target, screen)
        point as AgentModeTapPoint.Resolved
        assertEquals(540, point.x)
        assertEquals(200, point.y)
        assertEquals(4, point.elementId)
        assertEquals("", point.note)
    }

    @Test
    fun tapPointRefusesAnAncestorThatIsWholeScreenContainer() {
        val target = element(
            id = 9,
            left = 40,
            top = 100,
            right = 400,
            bottom = 160,
            tapTargetId = 1,
            tapTargetBounds = screen,
        )
        val point = resolveAgentModeTapPoint(target, screen)
        point as AgentModeTapPoint.Resolved
        assertEquals(9, point.elementId)
        assertEquals("clickable_ancestor_too_large", point.note)
    }

    @Test
    fun tapPointReportsAnElementThatIsFullyOffscreen() {
        val target = element(3, 0, 2500, 200, 2600, signals = AgentModeElementSignals(clickable = true))
        val point = resolveAgentModeTapPoint(target, screen)
        assertTrue(point is AgentModeTapPoint.Unresolved)
        assertEquals("element_is_offscreen", (point as AgentModeTapPoint.Unresolved).reason)
    }

    @Test
    fun tapPointNotesADisabledClickable() {
        val target = element(
            5,
            0,
            0,
            200,
            100,
            signals = AgentModeElementSignals(clickable = true, enabled = false),
        )
        val point = resolveAgentModeTapPoint(target, screen) as AgentModeTapPoint.Resolved
        assertEquals("element_is_disabled", point.note)
    }

    @Test
    fun targetResolutionFollowsAnElementThatMoved() {
        val previous = listOf(element(4, 100, 500, 300, 560, text = "发送"))
        val current = listOf(element(4, 100, 300, 300, 360, text = "发送"))
        val resolved = resolveAgentModeTarget(4, previous, current)
        assertTrue(resolved is AgentModeTargetResolution.Resolved)
        assertEquals(300, (resolved as AgentModeTargetResolution.Resolved).element.bounds.top)
    }

    @Test
    fun targetResolutionPicksTheNearestOfSeveralIdenticalRows() {
        val previous = listOf(element(2, 100, 1000, 900, 1100, text = "确认"))
        val current = listOf(
            element(7, 100, 200, 900, 300, text = "确认"),
            element(8, 100, 1020, 900, 1120, text = "确认"),
        )
        val resolved = resolveAgentModeTarget(2, previous, current) as AgentModeTargetResolution.Resolved
        assertEquals(8, resolved.element.id)
    }

    @Test
    fun targetResolutionReportsIdsItNeverHandedOut() {
        assertTrue(resolveAgentModeTarget(99, emptyList(), emptyList()) is AgentModeTargetResolution.Unknown)
    }

    @Test
    fun targetResolutionReportsAnElementThatIsGone() {
        val previous = listOf(element(1, 0, 0, 100, 100, text = "取消"))
        val resolved = resolveAgentModeTarget(1, previous, emptyList())
        assertTrue(resolved is AgentModeTargetResolution.Gone)
        assertEquals("取消", (resolved as AgentModeTargetResolution.Gone).label)
    }

    @Test
    fun hitTestPrefersTheTopmostWindowAndDeepestNode() {
        val background = element(1, 0, 0, 1080, 2400, windowIndex = 1, depth = 0)
        val overlay = element(2, 0, 0, 1080, 2400, windowIndex = 0, depth = 0)
        val overlayButton = element(3, 100, 100, 300, 200, windowIndex = 0, depth = 2)
        val hit = hitTestAgentModeElement(listOf(background, overlay, overlayButton), 150, 150)
        assertNotNull(hit)
        assertEquals(3, hit!!.id)
    }

    @Test
    fun hitTestReturnsNullWhenThePointIsOnNothing() {
        assertNull(hitTestAgentModeElement(listOf(element(1, 0, 0, 100, 100)), 500, 500))
    }

    @Test
    fun nearestActionableReportsTheClosestControl() {
        val elements = listOf(
            element(1, 0, 0, 100, 100, signals = AgentModeElementSignals(clickable = true)),
            element(2, 900, 900, 1000, 1000, signals = AgentModeElementSignals(clickable = true)),
            element(3, 500, 500, 600, 600),
        )
        assertEquals(2, nearestActionableAgentModeElement(elements, 880, 880)?.id)
    }

    @Test
    fun diffReportsAddedRemovedAndChanged() {
        val previous = listOf(
            element(1, 0, 0, 100, 100, text = "A"),
            element(2, 0, 200, 100, 300, text = "B"),
        )
        val current = listOf(
            element(1, 0, 0, 100, 100, text = "A"),
            element(2, 0, 260, 100, 360, text = "B"),
            element(3, 0, 400, 100, 500, text = "C"),
        )
        val delta = diffAgentModeElements(previous, current)
        assertEquals(listOf(3), delta.added.map { it.id })
        assertTrue(delta.removed.isEmpty())
        assertEquals(listOf(2), delta.changed.map { it.id })
        assertEquals(2, delta.total)
    }
}
