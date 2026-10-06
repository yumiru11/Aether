package com.zhousl.aether.agentmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeElementFormatTest {
    private fun element(
        id: Int,
        left: Int = 0,
        top: Int = id * 100,
        right: Int = 200,
        bottom: Int = top + 80,
        className: String = "android.widget.TextView",
        text: String = "",
        description: String = "",
        windowIndex: Int = 0,
        signals: AgentModeElementSignals = AgentModeElementSignals(clickable = true),
    ): AgentModeElement = AgentModeElement(
        id = id,
        windowIndex = windowIndex,
        className = className,
        text = text,
        description = description,
        bounds = AgentModeBounds(left, top, right, bottom),
        depth = 1,
        signals = signals,
    )

    @Test
    fun rowKeepsTheFreeTextFieldLast() {
        val row = agentModeElementRow(element(3, text = "获取验证码", className = "android.widget.Button"))
        val fields = row.split(' ')
        assertEquals("3", fields[0])
        assertEquals("Button", fields[1])
        assertEquals("c", fields[2])
        assertEquals("0,300,200,380", fields[3])
        assertEquals("获取验证码", fields.drop(4).joinToString(" "))
    }

    @Test
    fun rowMarksContentDescriptionsWithAtSign() {
        val row = agentModeElementRow(element(1, text = "", description = "返回"))
        assertTrue(row.endsWith(" @返回"))
    }

    @Test
    fun rowPointsAtTheAncestorWhenTheAncestorIsTheTapTarget() {
        val target = AgentModeElement(
            id = 9,
            windowIndex = 0,
            className = "android.widget.TextView",
            text = "登录",
            description = "",
            bounds = AgentModeBounds(0, 0, 200, 80),
            depth = 2,
            signals = AgentModeElementSignals(),
            tapTargetId = 4,
            tapTargetBounds = AgentModeBounds(0, 0, 400, 80),
        )
        assertTrue(agentModeElementRow(target).endsWith("登录 ->4"))
    }

    @Test
    fun flagsCoverEverySignalInAStableOrder() {
        assertEquals(
            "cltsifkx",
            elementFlags(
                element(
                    1,
                    signals = AgentModeElementSignals(
                        clickable = true,
                        longClickable = true,
                        editable = true,
                        scrollable = true,
                        focusable = true,
                        focused = true,
                        checkable = true,
                        checked = true,
                        selected = true,
                    ),
                ),
            ),
        )
        assertEquals("u", elementFlags(element(1, signals = AgentModeElementSignals(checkable = true))))
        assertEquals("d", elementFlags(element(1, signals = AgentModeElementSignals(enabled = false))))
        assertEquals("-", elementFlags(element(1, signals = AgentModeElementSignals())))
    }

    @Test
    fun classNameIsShortenedForEveryCommonPackage() {
        assertEquals("TextView", shortClassName("android.widget.TextView"))
        assertEquals("View", shortClassName("android.view.View"))
        assertEquals("WebView", shortClassName("android.webkit.WebView"))
        assertEquals("Button", shortClassName("com.example.app.ui.Button"))
        assertEquals("View", shortClassName(""))
    }

    @Test
    fun longLabelKeepsItsTail() {
        val tail = "?orderId=jiwY0lNldwOw"
        val label = "x".repeat(400) + tail
        val shortened = shortenLabel(label)
        assertTrue(shortened.length < label.length)
        assertTrue(shortened.endsWith(tail))
        assertTrue(shortened.contains("[cut "))
    }

    @Test
    fun shortLabelIsLeftAlone() {
        assertEquals("登录", shortenLabel("登录"))
    }

    @Test
    fun filteringDropsContainersDuplicatesAndInvisibleNodes() {
        val container = element(1, signals = AgentModeElementSignals(), text = "")
        val label = element(2, text = "手机号", signals = AgentModeElementSignals())
        // Same rectangle and same label as the row above: Android nests containers this way and the
        // duplicate carries no information of its own.
        val duplicate = element(3, top = 200, bottom = 280, text = "手机号", signals = AgentModeElementSignals())
        val kept = filterAgentModeElements(listOf(container, label, duplicate))
        assertEquals(listOf(2), kept.map { it.id })
    }

    @Test
    fun filteringKeepsContainersWhenInteractiveOnlyIsOff() {
        val container = element(1, signals = AgentModeElementSignals(), text = "")
        val kept = filterAgentModeElements(
            listOf(container),
            AgentModeElementFormatOptions(interactiveOnly = false),
        )
        assertEquals(listOf(1), kept.map { it.id })
    }

    @Test
    fun filteringAppliesQueryAndRegion() {
        val first = element(1, top = 0, bottom = 80, text = "登录")
        val second = element(2, top = 1000, bottom = 1080, text = "注册")
        assertEquals(
            listOf(2),
            filterAgentModeElements(listOf(first, second), AgentModeElementFormatOptions(query = "注册")).map { it.id },
        )
        assertEquals(
            listOf(1),
            filterAgentModeElements(
                listOf(first, second),
                AgentModeElementFormatOptions(region = AgentModeBounds(0, 0, 1080, 500)),
            ).map { it.id },
        )
    }

    @Test
    fun renderKeepsScreenOrderAndReportsTheFullCount() {
        val elements = listOf(
            element(3, windowIndex = 1, top = 10, bottom = 90, text = "第二个窗口"),
            element(1, top = 500, bottom = 580, text = "下面"),
            element(2, top = 100, bottom = 180, text = "上面"),
        )
        val rendered = renderAgentModeElements("status", elements)
        val rows = rendered.text.lines().filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("status") }
        assertEquals(listOf("2", "1", "3"), rows.map { it.substringBefore(' ') })
        assertEquals(3, rendered.total)
        assertEquals(3, rendered.shown)
        assertEquals(0, rendered.omitted)
    }

    @Test
    fun elementCapReportsOmissionsWithoutLosingTheStatus() {
        val elements = (1..5).map { element(it, text = "row" + it) }
        val rendered = renderAgentModeElements("status", elements, AgentModeElementFormatOptions(maxElements = 2))
        assertEquals(5, rendered.total)
        assertEquals(2, rendered.shown)
        assertEquals(3, rendered.omitted)
        assertTrue(rendered.text.startsWith("status"))
        assertTrue(rendered.text.lines().any { it.contains("omitted=3") })
    }

    @Test
    fun tinyBudgetReportsWhereTheCutStarted() {
        val elements = (1..3).map { element(it, top = it * 100, bottom = it * 100 + 80, text = "row") }
        val rendered = renderAgentModeElements("s", elements, AgentModeElementFormatOptions(budget = 1))
        assertEquals(0, rendered.shown)
        assertEquals(3, rendered.omitted)
        assertTrue(rendered.text.contains("from_y=100"))
    }

    @Test
    fun deltaRendersOneMarkedLinePerChange() {
        val previous = listOf(element(1, text = "A"), element(2, text = "B"))
        val current = listOf(element(1, text = "A", top = 900, bottom = 980), element(3, text = "C"))
        val delta = diffAgentModeElements(previous, current)
        val rendered = renderAgentModeElementDelta(delta)
        assertNotNull(rendered)
        assertTrue(rendered!!.contains("+3"))
        assertTrue(rendered.contains("-2"))
        assertTrue(rendered.contains("~1"))
    }

    @Test
    fun deltaRefusesAChangeTooLargeToSummarize() {
        val previous = emptyList<AgentModeElement>()
        val current = (1..20).map { element(it, text = "row" + it) }
        assertNull(renderAgentModeElementDelta(diffAgentModeElements(previous, current)))
        assertNull(renderAgentModeElementDelta(diffAgentModeElements(emptyList(), emptyList())))
    }
}
