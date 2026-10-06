package com.zhousl.aether.agentmode

/**
 * Android 11 is the first release whose accessibility API exposes the windows of a display other than
 * the default one: until Android 10 the service connection only offers a flat window list with no
 * display id, so a virtual display cannot be read no matter which private API is reached for.
 */
internal const val AgentModeMultiDisplayAccessibilityApi = 30

/** How the reader reaches the display, reported verbatim so a failure is diagnosable. */
internal enum class AgentModeElementReaderMode(val storageValue: String) {
    /** The automation instance is built from a display-scoped context, so its own window list is ours. */
    DisplayScoped("display_scoped"),

    /** Windows of every display are enumerated and filtered down to the Agent Mode display. */
    AllDisplays("all_displays"),

    /** No path to the accessibility subsystem is available on this device. */
    None("none"),
}

/**
 * Why the element channel is or is not usable.
 *
 * Every value is reported to the model together with [reason], because the issue that asked for this
 * channel also asked for the opposite of a silent fallback: a device that cannot serve elements has
 * to say so, or the model is left guessing why it only ever sees screenshots.
 */
internal enum class AgentModeElementAvailability(
    val storageValue: String,
    val reason: String,
) {
    Available("available", ""),

    DeviceApi(
        "device_api",
        "This Android version does not expose the windows of a non-default display to the " +
            "accessibility API, so only screenshots are available.",
    ),

    Busy(
        "busy",
        "Another tool currently holds the system's single UiAutomation slot (adb uiautomator or " +
            "Android Studio Layout Inspector). Stop it, or retry in a moment.",
    ),

    PermissionDenied(
        "permission_denied",
        "The Agent Mode service was not allowed to connect to the accessibility subsystem.",
    ),

    RegistrationFailed(
        "registration_failed",
        "Connecting to the accessibility subsystem failed on this device.",
    ),

    NoWindows(
        "no_windows",
        "The accessibility subsystem reported no window on the Agent Mode display. The app may not " +
            "have drawn yet, or Agent Mode may be acting on a different Android user than the one " +
            "whose apps are on the display.",
    ),
    ;

    val isAvailable: Boolean get() = this == Available
}

internal data class AgentModeElementCapability(
    val availability: AgentModeElementAvailability,
    val readerMode: AgentModeElementReaderMode,
    /** Extra device-specific detail, empty when there is nothing to add to [reason]. */
    val detail: String = "",
    val callerUser: Int = 0,
    val serviceUser: Int = 0,
) {
    val isAvailable: Boolean get() = availability.isAvailable

    companion object {
        fun unavailable(
            availability: AgentModeElementAvailability,
            detail: String = "",
        ): AgentModeElementCapability = AgentModeElementCapability(
            availability = availability,
            readerMode = AgentModeElementReaderMode.None,
            detail = detail,
        )
    }
}

/** The gate that no amount of reflection can work around, kept separate so it can be unit-tested. */
internal fun agentModeElementAvailabilityForSdk(sdkInt: Int): AgentModeElementAvailability =
    if (sdkInt >= AgentModeMultiDisplayAccessibilityApi) {
        AgentModeElementAvailability.Available
    } else {
        AgentModeElementAvailability.DeviceApi
    }

/**
 * Classifies a failed connection.
 *
 * The system allows exactly one UiAutomation at a time and rejects a second registration with
 * "already registered". That is a normal, temporary condition, not a broken device, so it gets its
 * own reason and its own advice instead of being reported as a generic failure.
 */
internal fun agentModeElementAvailabilityForFailure(cause: Throwable): AgentModeElementAvailability {
    val message = (cause.message ?: "").lowercase()
    return when {
        message.contains("already registered") -> AgentModeElementAvailability.Busy
        cause is SecurityException || message.contains("permission") ->
            AgentModeElementAvailability.PermissionDenied

        else -> AgentModeElementAvailability.RegistrationFailed
    }
}
