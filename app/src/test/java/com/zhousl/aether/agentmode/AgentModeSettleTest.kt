package com.zhousl.aether.agentmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeSettleTest {
    private fun signature(vararg pairs: Pair<String, String>) =
        AgentModeScreenSignature(pairs.toMap())

    @Test
    fun trackerSettlesAfterTwoIdenticalSamples() {
        val tracker = AgentModeSettleTracker()
        tracker.record(signature("windows" to "1"))
        assertFalse(tracker.isSettled)
        tracker.record(signature("windows" to "1"))
        assertTrue(tracker.isSettled)
        assertEquals(2, tracker.samples)
    }

    @Test
    fun trackerRestartsWhenTheScreenChangesAgain() {
        val tracker = AgentModeSettleTracker()
        tracker.record(signature("windows" to "1"))
        tracker.record(signature("windows" to "1"))
        assertTrue(tracker.isSettled)

        val moving = AgentModeSettleTracker()
        moving.record(signature("windows" to "1"))
        moving.record(signature("windows" to "2"))
        moving.record(signature("windows" to "1"))
        assertFalse(moving.isSettled)
        moving.record(signature("windows" to "1"))
        assertTrue(moving.isSettled)
    }

    @Test
    fun trackerTreatsAnEmptySignatureAsStable() {
        val tracker = AgentModeSettleTracker()
        tracker.record(AgentModeScreenSignature.Empty)
        tracker.record(AgentModeScreenSignature.Empty)
        assertTrue(tracker.isSettled)
    }

    @Test
    fun effectReportsWhichSignalsDiffer() {
        val before = signature("windows" to "1", "focus" to "A", "content" to "x")
        val after = signature("windows" to "1", "focus" to "B", "content" to "x")
        val report = compareAgentModeScreenSignatures(before, after)
        assertEquals(AgentModeEffect.Changed, report.effect)
        assertEquals(listOf("focus"), report.changedSignals)
    }

    @Test
    fun effectReportsUnchangedForIdenticalSignatures() {
        val report = compareAgentModeScreenSignatures(
            signature("windows" to "1"),
            signature("windows" to "1"),
        )
        assertEquals(AgentModeEffect.Unchanged, report.effect)
        assertTrue(report.changedSignals.isEmpty())
    }

    @Test
    fun effectIsUnknownWhenASideIsMissing() {
        val missingBefore = compareAgentModeScreenSignatures(null, signature("windows" to "1"))
        assertEquals(AgentModeEffect.Unknown, missingBefore.effect)
        val missingAfter = compareAgentModeScreenSignatures(signature("windows" to "1"), null)
        assertEquals(AgentModeEffect.Unknown, missingAfter.effect)
    }

    @Test
    fun effectDetectsASignalThatOnlyOneSideHas() {
        val report = compareAgentModeScreenSignatures(
            signature("windows" to "1"),
            signature("windows" to "1", "focus" to "A"),
        )
        assertEquals(AgentModeEffect.Changed, report.effect)
        assertEquals(listOf("focus"), report.changedSignals)
    }
}
