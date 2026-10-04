package com.zhousl.aether.agentmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeUserScopeTest {
    @Test
    fun ownerUserUidsResolveToUserZero() {
        assertEquals(0, agentModeUserIdFromUid(0))
        assertEquals(0, agentModeUserIdFromUid(10_000))
        assertEquals(0, agentModeUserIdFromUid(10_199))
    }

    @Test
    fun secondaryUserAndWorkProfileUidsResolveToTheirUser() {
        // Issue #100 was reported from a secondary user (10); work profiles are the same scheme.
        assertEquals(10, agentModeUserIdFromUid(10 * 100_000 + 10_123))
        assertEquals(11, agentModeUserIdFromUid(11 * 100_000 + 10_123))
        assertEquals(999, agentModeUserIdFromUid(999 * 100_000))
    }

    @Test
    fun uidBoundariesGoToExactlyOneUser() {
        // 99_999 is the owner user's last uid; the next uid already belongs to user 1.
        assertEquals(0, agentModeUserIdFromUid(99_999))
        assertEquals(1, agentModeUserIdFromUid(100_000))
    }

    @Test
    fun negativeUidsAreNotMistakenForHighUsers() {
        // Integer division truncates towards zero, so a corrupt negative uid must not end up looking
        // like a high user id.
        assertEquals(0, agentModeUserIdFromUid(-1))
        assertEquals(-1, agentModeUserIdFromUid(-100_000))
    }

    @Test
    fun highestSupportedUserIdStillFitsInAUidRange() {
        // Agent Mode rejects ids above this bound: userId * AgentModePerUserUidRange would overflow
        // and the wrapped-around uid would alias the call back to the owner user.
        assertEquals(21_474, AgentModeMaxUserId)
        assertTrue(AgentModeMaxUserId * AgentModePerUserUidRange > 0)
        assertEquals(
            AgentModeMaxUserId,
            agentModeUserIdFromUid(AgentModeMaxUserId * AgentModePerUserUidRange),
        )
    }

    @Test
    fun systemUidsResolveToTheOwnerUser() {
        // Shizuku and the su fallback start the user service as shell (2000) or root (0). Both live
        // in user 0, which is exactly why the service has to be told which user it acts for.
        assertEquals(0, agentModeUserIdFromUid(2000))
        assertEquals(0, agentModeUserIdFromUid(1000))
        assertEquals(0, agentModeUserIdFromUid(0))
    }
}
