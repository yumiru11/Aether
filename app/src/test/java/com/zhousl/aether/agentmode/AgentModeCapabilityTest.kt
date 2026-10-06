package com.zhousl.aether.agentmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeCapabilityTest {
    @Test
    fun sdkGateRejectsAndroidTenAndBelow() {
        assertEquals(
            AgentModeElementAvailability.DeviceApi,
            agentModeElementAvailabilityForSdk(29),
        )
        assertEquals(
            AgentModeElementAvailability.Available,
            agentModeElementAvailabilityForSdk(AgentModeMultiDisplayAccessibilityApi),
        )
    }

    @Test
    fun aSecondRegistrationIsReportedAsBusyNotBroken() {
        val cause = IllegalStateException("UiAutomationService already registered!")
        assertEquals(
            AgentModeElementAvailability.Busy,
            agentModeElementAvailabilityForFailure(cause),
        )
    }

    @Test
    fun aSecurityFailureIsReportedAsAPermissionProblem() {
        assertEquals(
            AgentModeElementAvailability.PermissionDenied,
            agentModeElementAvailabilityForFailure(SecurityException("denied")),
        )
    }

    @Test
    fun anythingElseIsReportedAsARegistrationFailure() {
        assertEquals(
            AgentModeElementAvailability.RegistrationFailed,
            agentModeElementAvailabilityForFailure(RuntimeException("boom")),
        )
    }

    @Test
    fun onlyAvailableIsUsableAndEveryOtherReasonExplainsItself() {
        assertTrue(AgentModeElementAvailability.Available.isAvailable)
        AgentModeElementAvailability.entries
            .filter { it != AgentModeElementAvailability.Available }
            .forEach { availability ->
                assertTrue(availability.reason.isNotBlank())
            }
    }
}
