package com.zhousl.aether.agentmode

/**
 * Android allocates [AgentModePerUserUidRange] uids per user, so a process uid also identifies the
 * user that process runs as. `UserHandle.getUserId` computes the same value but is hidden API and
 * cannot be referenced from app code, hence this constant.
 */
internal const val AgentModePerUserUidRange = 100_000

/**
 * The largest user id whose first uid still fits in an [Int]. A larger id would make
 * `userId * AgentModePerUserUidRange` overflow, and the wrapped-around value would alias the request
 * back to the owner user instead of the requested one.
 */
internal const val AgentModeMaxUserId = Int.MAX_VALUE / AgentModePerUserUidRange

/** Returns the Android user id that [uid] belongs to. */
internal fun agentModeUserIdFromUid(uid: Int): Int = uid / AgentModePerUserUidRange
