package com.zhousl.aether.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.Display
import android.view.Surface
import androidx.core.content.getSystemService
import com.zhousl.aether.agentmode.AgentModeLaunchSettleTimeoutMillis
import com.zhousl.aether.agentmode.AgentModeServiceHandle
import com.zhousl.aether.agentmode.AgentModeServiceLauncher
import com.zhousl.aether.agentmode.AgentModeSettleTimeoutMillis
import com.zhousl.aether.agentmode.IAetherAgentModeService
import com.zhousl.aether.termux.TermuxBashTool
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

private const val FallbackAgentDisplayWidth = 720
private const val FallbackAgentDisplayHeight = 1280
private const val FallbackAgentDisplayDensityDpi = 320
private const val AgentDisplayName = "aether-agent-mode"
private const val AgentModeCaptureExtension = "jpg"
private const val AgentModeCaptureMimeType = "image/jpeg"
private const val AgentModeCaptureMaxEdge = 1280
private const val AgentModeCoordinateSpace = "normalized_0_1000"
private const val AgentModeCaptureJpegQuality = 85

/**
 * Quality for a cropped capture. A crop is small, so the extra bytes cost little, and the extra
 * sharpness is exactly what a coordinate read off the image depends on.
 */
private const val AgentModeCaptureRegionJpegQuality = 92
private const val AgentModeDefaultWaitTimeoutMillis = 5_000
private const val AgentModeMaxWaitTimeoutMillis = 60_000
private const val AgentModeWaitPollIntervalMillis = 150L
private const val AgentModeDefaultScrollSteps = 6
private const val AgentModeScrollStepDelayMillis = 120L
private const val AgentModeMaxBatchSteps = 20
private const val ShizukuPermissionRequestCode = 4201

/**
 * Steps a batch may contain. Every step is validated before the first one runs, so a typo in the last
 * entry cannot leave the device half-way through a sequence.
 */
private val AgentModeBatchStepActions = setOf(
    "observe",
    "tap",
    "long_press",
    "text",
    "scroll",
    "key",
    "swipe",
    "launch",
    "wait",
)
private const val RootAuthorizationProbeTimeoutMillis = 2_000L
private const val AgentModeServiceStartTimeoutMillis = 20_000L

private val ShizukuManagerPackages = listOf(
    "moe.shizuku.privileged.api",
    "moe.shizuku.manager",
)

data class AgentModeDisplayState(
    val isActive: Boolean = false,
    val displayId: Int? = null,
    val width: Int = FallbackAgentDisplayWidth,
    val height: Int = FallbackAgentDisplayHeight,
    val displays: List<AgentModeDisplayInfo> = emptyList(),
    val latestPreviewPath: String = "",
    val latestWorkspacePath: String = "",
    val cursorX: Int? = null,
    val cursorY: Int? = null,
    val cursorAnimationDurationMillis: Int = 220,
    val isLivePreviewActive: Boolean = false,
    val lastUpdatedMillis: Long = 0L,
    val status: String = "",
)

data class AgentModeDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isAetherDisplay: Boolean,
)

data class AgentModeInstalledAppInfo(
    val packageName: String,
    val appName: String,
    val activityName: String,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
)

enum class AgentModeAuthorizationIssue {
    Disabled,
    Ready,
    ShizukuNotInstalled,
    ShizukuNotRunning,
    ShizukuPermissionMissing,
    ShizukuPermissionDenied,
    RootUnavailable,
    RootPermissionMissing,
    RootPermissionDenied,
    Error,
}

data class AgentModeAuthorizationState(
    val issue: AgentModeAuthorizationIssue = AgentModeAuthorizationIssue.Disabled,
    val detail: String = "",
) {
    val isReady: Boolean
        get() = issue == AgentModeAuthorizationIssue.Ready
}

class AgentModeController(
    private val context: Context,
    private val bashTool: TermuxBashTool,
    private val runtimeWorkspaceFileBridge: RuntimeWorkspaceFileBridge,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val displayManager = context.getSystemService<DisplayManager>()!!
    private val cacheDirectory = File(context.cacheDir, "agent-mode").apply { mkdirs() }
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureMutex = Mutex()
    private val shizukuServiceMutex = Mutex()
    private val _displayState = MutableStateFlow(AgentModeDisplayState())
    private val _authorizationState = MutableStateFlow(AgentModeAuthorizationState())
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuPermissionRequestCode) {
                AetherAnalytics.capture(
                    event = "permission result",
                    properties = mapOf(
                        "permission" to "shizuku",
                        "source" to "agent_mode_authorization",
                        "granted" to (grantResult == PackageManager.PERMISSION_GRANTED),
                        "result" to if (grantResult == PackageManager.PERMISSION_GRANTED) "granted" else "denied",
                    ),
                )
                _authorizationState.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    diagnosticLogger.event(
                        category = "agent_mode",
                        event = "shizuku_permission_granted",
                    )
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.Ready,
                        detail = "Shizuku permission is granted.",
                    )
                } else {
                    diagnosticLogger.event(
                        category = "agent_mode",
                        event = "shizuku_permission_denied",
                        level = "warn",
                    )
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.ShizukuPermissionDenied,
                        detail = "Shizuku permission was denied. Grant Aether permission in Shizuku before using Agent Mode.",
                    )
                }
            }
        }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        if (_authorizationState.value.issue != AgentModeAuthorizationIssue.Disabled) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Shizuku stopped. Start Shizuku, then refresh Agent Mode status.",
            )
        }
        clearShizukuService("Shizuku stopped. Agent Mode virtual display was reset.")
    }

    private var shizukuDisplayId: Int? = null
    private var displayOwnerMethod: AgentModeAuthorizationMethod? = null
    private var displayOwnerBinder: IBinder? = null
    private var shizukuService: IAetherAgentModeService? = null
    private var shizukuServiceHandle: AgentModeServiceHandle? = null
    private var rootService: IAetherAgentModeService? = null
    private var rootServiceHandle: AgentModeServiceHandle? = null
    @Volatile
    private var previewSurface: Surface? = null

    val displayState: StateFlow<AgentModeDisplayState> = _displayState.asStateFlow()
    val authorizationState: StateFlow<AgentModeAuthorizationState> = _authorizationState.asStateFlow()

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        }
        runCatching {
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        }
    }

    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        argumentsJson: String,
    ): String = withContext(Dispatchers.IO) {
        if (!settings.agentModeAuthorizationEnabled) {
            captureAgentModeFailed(
                settings = settings,
                action = "unknown",
                reason = "authorization_disabled",
                message = "Agent Mode is not authorized.",
            )
            return@withContext JSONObject().apply {
                put("ok", false)
                put("errmsg", "Agent Mode is not authorized. Enable it in Settings > Agent Mode first.")
            }.toString()
        }

        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = "unknown",
                    reason = "invalid_arguments",
                    message = "Arguments were not valid JSON.",
                )
            }
        val action = arguments.optString("action").trim().lowercase()
        diagnosticLogger.event(
            category = "agent_mode",
            event = "action_start",
            details = mapOf(
                "action" to action.ifBlank { "unknown" },
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )

        runCatching {
            when (action) {
            "start" -> {
                ensureDisplay(settings)
                observeResult(
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                    termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                    arguments = arguments,
                    settleTimeoutMillis = AgentModeSettleTimeoutMillis,
                    fallbackDelayMillis = 350,
                )
            }
            "status" -> statusResult(settings)
            "list_apps", "apps", "installed_apps" -> listInstalledAppsResult(settings, arguments)
            "launch" -> {
                ensureDisplay(settings)
                val target = arguments.optString("target").trim()
                if (target.isBlank()) {
                    invalidArguments("Missing required 'target' argument.")
                } else {
                    launchTarget(settings, target)
                    observeResult(
                        settings = settings,
                        workspaceDirectory = workspaceDirectory,
                        termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                        arguments = arguments,
                        settleTimeoutMillis = AgentModeLaunchSettleTimeoutMillis,
                        fallbackDelayMillis = 900,
                    )
                }
            }
            "observe" -> observeResult(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
            )
            "tap" -> gestureResult(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
                longPress = false,
            )
            "long_press" -> gestureResult(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
                longPress = true,
            )
            "swipe" -> {
                val displayId = ensureDisplay(settings)
                val start = resolvePoint(arguments, "x1", "y1")
                val end = resolvePoint(arguments, "x2", "y2")
                val durationMs = arguments.optInt("duration_ms", arguments.optInt("durationMs", 500))
                    .coerceIn(50, 10_000)
                when {
                    start is ResolvedPoint.Invalid -> invalidArguments(start.message)
                    end is ResolvedPoint.Invalid -> invalidArguments(end.message)
                    start is ResolvedPoint.Valid && end is ResolvedPoint.Valid -> {
                        updateCursorPosition(start.x, start.y, animationDurationMillis = 80)
                        controllerScope.launch {
                            delay(40)
                            updateCursorPosition(end.x, end.y, animationDurationMillis = durationMs)
                        }
                        requireAgentModeService(settings).swipe(displayId, start.x, start.y, end.x, end.y, durationMs)
                        perceive(
                            settings = settings,
                            workspaceDirectory = workspaceDirectory,
                            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                            arguments = arguments,
                            base = actionSucceeded("swipe"),
                            settleTimeoutMillis = AgentModeSettleTimeoutMillis,
                            delayMillis = durationMs.toLong() + 250,
                        )
                    }
                    else -> invalidArguments("x1, y1, x2, and y2 are required.")
                }
            }
            "scroll" -> scrollResult(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
            )
            "key" -> {
                val displayId = ensureDisplay(settings)
                val keyCode = arguments.optString("key").trim()
                if (keyCode.isBlank()) {
                    invalidArguments("Missing required 'key' argument.")
                } else {
                    requireAgentModeService(settings).key(displayId, keyCode)
                    perceive(
                        settings = settings,
                        workspaceDirectory = workspaceDirectory,
                        termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                        arguments = arguments,
                        base = actionSucceeded("key"),
                        settleTimeoutMillis = AgentModeSettleTimeoutMillis,
                        delayMillis = 300,
                    )
                }
            }
            "text" -> textResult(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
            )
            "wait" -> waitResult(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
            )
            "batch" -> batchResult(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
            )
            "screenshot" -> screenshotResult(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
            )
            "stop" -> {
                releaseDisplay()
                JSONObject().apply {
                    put("ok", true)
                    put("stdout", "Agent Mode virtual display stopped.")
                }.toString()
            }
            else -> invalidArguments("Unsupported action '$action'.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = action.ifBlank { "unknown" },
                    reason = "unsupported_action",
                    message = "Unsupported action '$action'.",
                )
            }
            }.also {
                val result = runCatching { JSONObject(it) }.getOrNull()
                diagnosticLogger.event(
                    category = "agent_mode",
                    event = "action_end",
                    level = if (result?.optBoolean("ok", true) == false) "warn" else "info",
                    details = mapOf(
                        "action" to action.ifBlank { "unknown" },
                        "ok" to (result?.optBoolean("ok", true) ?: true),
                        "display_id" to _displayState.value.displayId,
                        "display_active" to _displayState.value.isActive,
                        "message" to result?.optString("errmsg").orEmpty(),
                    ),
                )
            }
        }.getOrElse { throwable ->
            captureAgentModeFailed(
                settings = settings,
                action = action.ifBlank { "unknown" },
                reason = "exception",
                message = throwable.message ?: throwable.javaClass.simpleName,
            )
            toolError(
                message = throwable.message ?: throwable.javaClass.simpleName,
                action = action,
            )
        }
    }

    suspend fun refreshAuthorization(settings: AppSettings) {
        if (
            shizukuDisplayId != null &&
            displayOwnerMethod != null &&
            displayOwnerMethod != settings.agentModeAuthorizationMethod
        ) {
            releaseDisplay("Virtual display reset because Agent Mode authorization method changed.")
        }
        _authorizationState.value = inspectAuthorization(settings).also { state ->
            diagnosticLogger.event(
                category = "agent_mode",
                event = "authorization_refreshed",
                level = if (state.isReady || state.issue == AgentModeAuthorizationIssue.Disabled) "info" else "warn",
                details = mapOf(
                    "issue" to state.issue.name,
                    "detail" to state.detail,
                    "method" to settings.agentModeAuthorizationMethod.storageValue,
                    "enabled" to settings.agentModeAuthorizationEnabled,
                ),
            )
        }
    }

    fun requestShizukuPermission(): AgentModeAuthorizationState {
        val current = inspectShizukuAuthorization()
        if (current.issue == AgentModeAuthorizationIssue.Ready) {
            _authorizationState.value = current
            return current
        }
        if (
            current.issue != AgentModeAuthorizationIssue.ShizukuPermissionMissing &&
            current.issue != AgentModeAuthorizationIssue.ShizukuPermissionDenied
        ) {
            _authorizationState.value = current
            return current
        }

        return runCatching {
            AetherAnalytics.capture(
                event = "permission requested",
                properties = mapOf(
                    "permission" to "shizuku",
                    "source" to "agent_mode_authorization",
                    "current_issue" to current.issue.name.lowercase(),
                ),
            )
            Shizuku.requestPermission(ShizukuPermissionRequestCode)
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                detail = "Confirm the Shizuku permission prompt, then refresh Agent Mode status.",
            )
        }.getOrElse { throwable ->
            AetherAnalytics.capture(
                event = "permission result",
                properties = mapOf(
                    "permission" to "shizuku",
                    "source" to "agent_mode_authorization",
                    "granted" to false,
                    "result" to "request_failed",
                    "error" to (throwable.message ?: throwable.javaClass.simpleName),
                ),
            )
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = throwable.message ?: "Failed to request Shizuku permission.",
            )
        }.also { _authorizationState.value = it }
    }

    private suspend fun ensureDisplay(settings: AppSettings): Int {
        val service = requireAgentModeService(settings)
        val serviceBinder = service.asBinder()
        shizukuDisplayId?.let { displayId ->
            if (isCurrentDisplayOwner(settings, serviceBinder)) {
                return displayId
            }
            releaseDisplay("Virtual display reset because Agent Mode authorization service changed.")
        }
        val displaySpec = currentDeviceDisplaySpec()
        val displayId = service.createOwnedDisplay(
            AgentDisplayName,
            displaySpec.width,
            displaySpec.height,
            displaySpec.densityDpi,
        )
        shizukuDisplayId = displayId
        displayOwnerMethod = settings.agentModeAuthorizationMethod
        displayOwnerBinder = serviceBinder
        diagnosticLogger.event(
            category = "agent_mode",
            event = "display_created",
            details = mapOf(
                "display_id" to displayId,
                "width" to displaySpec.width,
                "height" to displaySpec.height,
                "density_dpi" to displaySpec.densityDpi,
                "method" to settings.agentModeAuthorizationMethod.storageValue,
            ),
        )
        _displayState.value = AgentModeDisplayState(
            isActive = true,
            displayId = displayId,
            width = displaySpec.width,
            height = displaySpec.height,
            displays = currentDisplays(settings, displayId),
            status = "${settings.agentModeAuthorizationMethod.displayName} virtual display ready",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        attachCurrentPreviewSurface(settings, displayId)
        return displayId
    }

    fun stopDisplay() {
        releaseDisplay()
    }

    suspend fun attachPreviewSurface(settings: AppSettings, surface: Surface) = withContext(Dispatchers.IO) {
        previewSurface = surface
        val displayId = currentManagedDisplayId(settings) ?: return@withContext
        attachCurrentPreviewSurface(settings, displayId)
    }

    suspend fun detachPreviewSurface(settings: AppSettings, surface: Surface) = withContext(Dispatchers.IO) {
        if (previewSurface !== surface) return@withContext
        previewSurface = null
        val displayId = currentManagedDisplayId(settings) ?: run {
            _displayState.value = _displayState.value.copy(
                isLivePreviewActive = false,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
            return@withContext
        }
        runCatching {
            requireAgentModeService(settings).detachPreviewSurface(displayId)
        }
        val state = _displayState.value
        _displayState.value = state.copy(
            isLivePreviewActive = false,
            status = if (state.isActive) {
                "${settings.agentModeAuthorizationMethod.displayName} virtual display ready"
            } else {
                state.status
            },
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    suspend fun refreshDisplays(settings: AppSettings) {
        currentManagedDisplayId(settings)
        val state = _displayState.value
        _displayState.value = state.copy(
            displays = currentDisplays(settings, state.displayId),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun attachCurrentPreviewSurface(settings: AppSettings, displayId: Int) {
        val surface = previewSurface?.takeIf { it.isValid } ?: return
        runCatching {
            requireAgentModeService(settings).attachPreviewSurface(displayId, surface)
        }.onSuccess {
            val state = _displayState.value
            if (state.displayId == displayId && state.isActive) {
                _displayState.value = state.copy(
                    isLivePreviewActive = true,
                    status = "Streaming virtual display",
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            }
        }.onFailure { throwable ->
            val state = _displayState.value
            if (state.displayId == displayId && state.isActive) {
                _displayState.value = state.copy(
                    isLivePreviewActive = false,
                    status = throwable.message ?: "Live preview surface is not available.",
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun launchTarget(
        settings: AppSettings,
        target: String,
    ) {
        val displayId = ensureDisplay(settings)
        val launchPackage = resolveLaunchPackage(settings, target)
            ?: error("No launchable app matched '$target'. Try a package name such as com.android.chrome, or a shorter app label.")
        requireAgentModeService(settings).launchPackage(launchPackage, displayId)
    }

    private suspend fun resolveLaunchPackage(
        settings: AppSettings,
        target: String,
    ): String? {
        val normalizedTarget = target.trim().lowercase()
        if (normalizedTarget.isBlank()) return null
        context.packageManager.getLaunchIntentForPackage(target)?.let { return target }

        val launchables = currentInstalledApps(settings)
        val tokens = normalizedTarget.split(Regex("\\s+"))
            .filter { it.length > 2 && it !in setOf("app", "browser", "managed") }
        return launchables.firstOrNull { app ->
            app.packageName.equals(normalizedTarget, ignoreCase = true) ||
                app.appName.equals(target, ignoreCase = true)
        }?.packageName ?: launchables.firstOrNull { app ->
            app.packageName.lowercase().contains(normalizedTarget) ||
                app.appName.lowercase().contains(normalizedTarget) ||
                app.activityName.lowercase().contains(normalizedTarget) ||
                tokens.any { token ->
                    app.packageName.lowercase().contains(token) ||
                        app.appName.lowercase().contains(token) ||
                        app.activityName.lowercase().contains(token)
                }
        }?.packageName ?: target.takeIf {
            it.matches(Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+"""))
        }
    }

    private suspend fun listInstalledAppsResult(
        settings: AppSettings,
        arguments: JSONObject,
    ): String {
        val query = arguments.optString("query").trim()
        val normalizedQuery = query.lowercase()
        val includeSystem = arguments.optBoolean(
            "include_system",
            arguments.optBoolean("includeSystem", true),
        )
        val maxResults = arguments.optInt(
            "max_results",
            arguments.optInt("maxResults", 500),
        ).coerceIn(1, 1_000)
        val apps = currentInstalledApps(settings)
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .filter { app ->
                normalizedQuery.isBlank() ||
                    app.packageName.lowercase().contains(normalizedQuery) ||
                    app.appName.lowercase().contains(normalizedQuery) ||
                    app.activityName.lowercase().contains(normalizedQuery)
            }
            .toList()
        val visibleApps = apps.take(maxResults)
        return JSONObject().apply {
            put("ok", true)
            put("count", apps.size)
            put("truncated", apps.size > visibleApps.size)
            put(
                "apps",
                JSONArray().apply {
                    visibleApps.forEach { app ->
                        put(
                            JSONObject().apply {
                                put("app_name", app.appName)
                                put("package_name", app.packageName)
                                put("activity_name", app.activityName)
                                put("enabled", app.isEnabled)
                                put("system", app.isSystemApp)
                                put("launchable", true)
                            }
                        )
                    }
                },
            )
            put(
                "stdout",
                buildString {
                    append("Found ")
                    append(apps.size)
                    append(if (includeSystem) " launchable apps." else " non-system launchable apps.")
                    if (apps.size > visibleApps.size) {
                        append(" Showing ")
                        append(visibleApps.size)
                        append(".")
                    }
                    visibleApps.forEach { app ->
                        append('\n')
                        append(app.appName)
                        append(" -> ")
                        append(app.packageName)
                    }
                },
            )
        }.toString()
    }

    private suspend fun currentInstalledApps(settings: AppSettings): List<AgentModeInstalledAppInfo> {
        val privilegedApps = runCatching {
            parseInstalledApps(requireAgentModeService(settings).listInstalledAppsJson())
        }.getOrNull()
        return (privilegedApps?.takeIf { it.isNotEmpty() } ?: currentInstalledAppsLocal())
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.appName.lowercase() }, { it.packageName }))
    }

    @Suppress("DEPRECATION")
    private fun currentInstalledAppsLocal(): List<AgentModeInstalledAppInfo> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val applicationInfo = activityInfo.applicationInfo ?: return@mapNotNull null
            AgentModeInstalledAppInfo(
                packageName = activityInfo.packageName.orEmpty(),
                appName = info.loadLabel(packageManager).toString(),
                activityName = activityInfo.name.orEmpty(),
                isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isEnabled = activityInfo.enabled && applicationInfo.enabled,
            )
        }
    }

    private fun parseInstalledApps(rawValue: String): List<AgentModeInstalledAppInfo> {
        val array = JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("package_name").ifBlank {
                    item.optString("packageName")
                }
                if (packageName.isBlank()) continue
                add(
                    AgentModeInstalledAppInfo(
                        packageName = packageName,
                        appName = item.optString("app_name").ifBlank {
                            item.optString("appName").ifBlank { packageName }
                        },
                        activityName = item.optString("activity_name").ifBlank {
                            item.optString("activityName")
                        },
                        isSystemApp = item.optBoolean("system"),
                        isEnabled = item.optBoolean("enabled", true),
                    )
                )
            }
        }
    }

    /**
     * Captures the display (or one rectangle of it) and returns the fields a result carries.
     *
     * A crop reports its own origin and scale, so converting a pixel read off the delivered image back
     * into a device coordinate is one addition and one multiplication rather than a guess: the whole
     * point of cropping is that the scale factor stops being a source of error.
     */
    private suspend fun captureFields(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        delayMillis: Long,
        region: Rect?,
        quality: Int,
    ): JSONObject {
        if (delayMillis > 0) delay(delayMillis)
        val captureId = "capture-" + System.currentTimeMillis()
        val previewFile = File(cacheDirectory, "$captureId.$AgentModeCaptureExtension")
        captureImageFile(settings, previewFile, region, quality)
        if (!previewFile.isFile || previewFile.length() <= 0L) {
            error("Agent Mode screenshot capture produced an empty file.")
        }
        val bytes = previewFile.readBytes()
        val latestPreviewFile = File(cacheDirectory, "latest.$AgentModeCaptureExtension")
        previewFile.copyTo(latestPreviewFile, overwrite = true)
        val previewPath = previewFile.absolutePath
        val workspacePath = "$workspaceDirectory/agent-mode/$captureId.$AgentModeCaptureExtension"
        runtimeWorkspaceFileBridge.writeWorkspaceBytes(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            absolutePath = workspacePath,
            bytes = bytes,
        ).getOrThrow()
        val displayId = currentManagedDisplayId(settings)
        val state = _displayState.value
        _displayState.value = state.copy(
            isActive = displayId != null,
            displayId = displayId,
            displays = currentDisplays(settings, displayId),
            latestPreviewPath = previewPath,
            latestWorkspacePath = workspacePath,
            lastUpdatedMillis = System.currentTimeMillis(),
            status = "Captured virtual display",
        )
        return JSONObject().apply {
            put("ok", true)
            put("display_id", displayId)
            put("width", state.width)
            put("height", state.height)
            val captureWidth = region?.width()?.takeIf { it > 0 } ?: state.width
            val captureHeight = region?.height()?.takeIf { it > 0 } ?: state.height
            val (imageWidth, imageHeight) = agentModeScreenshotSize(
                captureWidth,
                captureHeight,
                AgentModeCaptureMaxEdge,
            )
            put("image_width", imageWidth)
            put("image_height", imageHeight)
            put("coordinate_space", AgentModeCoordinateSpace)
            put("screenshot_path", workspacePath)
            put("preview_path", previewPath)
            put("screenshot_max_edge", AgentModeCaptureMaxEdge)
            put("screenshot_quality", quality)
            if (region != null) {
                val scaleX = if (imageWidth > 0) captureWidth.toDouble() / imageWidth.toDouble() else 1.0
                val scaleY = if (imageHeight > 0) captureHeight.toDouble() / imageHeight.toDouble() else 1.0
                put("screenshot_region", regionText(region))
                put("screenshot_scale_x", scaleX)
                put("screenshot_scale_y", scaleY)
                put(
                    "screenshot_hint",
                    "Inside this crop: device_x = " + region.left + " + image_x * " + formatScale(scaleX) +
                        ", device_y = " + region.top + " + image_y * " + formatScale(scaleY) +
                        ". image_width/image_height are the size of the image you were given.",
                )
            }
            // cursor_x/cursor_y are display pixels (kept for the UI); cursor_norm_* are the 0..1000 values to reuse.
            state.cursorX?.let {
                put("cursor_x", it)
                put("cursor_norm_x", normalizeAgentModePixel(it, state.width))
            }
            state.cursorY?.let {
                put("cursor_y", it)
                put("cursor_norm_y", normalizeAgentModePixel(it, state.height))
            }
            put("screenshot_mime_type", AgentModeCaptureMimeType)
            put("screenshot_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("stdout", "Captured Agent Mode screenshot: $workspacePath")
        }
    }

    private fun regionText(region: Rect): String =
        region.left.toString() + ',' + region.top + ',' + region.right + ',' + region.bottom

    /** Trims a scale factor so a conversion hint reads as "2" rather than "2.0000000001". */
    private fun formatScale(value: Double): String {
        val rounded = Math.round(value * 10_000.0) / 10_000.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun updateCursorPosition(
        x: Int,
        y: Int,
        animationDurationMillis: Int = 220,
    ) {
        val state = _displayState.value
        _displayState.value = state.copy(
            cursorX = x.coerceIn(0, state.width),
            cursorY = y.coerceIn(0, state.height),
            cursorAnimationDurationMillis = animationDurationMillis.coerceIn(80, 1_200),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun captureImageFile(
        settings: AppSettings,
        outputFile: File,
        region: Rect? = null,
        quality: Int = AgentModeCaptureJpegQuality,
    ) {
        val displayId = ensureDisplay(settings)
        captureMutex.withLock {
            outputFile.parentFile?.mkdirs()
            runCatching { outputFile.delete() }
            try {
                ParcelFileDescriptor.open(
                    outputFile,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                ).use { descriptor ->
                    if (region == null) {
                        requireAgentModeService(settings).captureImageToFd(
                            displayId,
                            descriptor,
                            AgentModeCaptureMaxEdge,
                            quality,
                        )
                    } else {
                        requireAgentModeService(settings).captureRegionToFd(
                            displayId,
                            descriptor,
                            AgentModeCaptureMaxEdge,
                            quality,
                            region.left,
                            region.top,
                            region.right,
                            region.bottom,
                        )
                    }
                }
            } catch (throwable: Throwable) {
                runCatching { outputFile.delete() }
                throw throwable
            }
        }
    }

    private suspend fun statusResult(settings: AppSettings): String {
        currentManagedDisplayId(settings)
        val state = _displayState.value
        val displays = currentDisplays(settings, state.displayId)
        _displayState.value = state.copy(
            displays = displays,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        return JSONObject().apply {
            put("ok", true)
            put("active", state.isActive)
            put("display_id", state.displayId)
            put("width", state.width)
            put("height", state.height)
            put("live_preview", state.isLivePreviewActive)
            put(
                "displays",
                org.json.JSONArray().apply {
                    displays.forEach { display ->
                        put(
                            JSONObject().apply {
                                put("display_id", display.displayId)
                                put("name", display.name)
                                put("width", display.width)
                                put("height", display.height)
                                put("is_aether_display", display.isAetherDisplay)
                            }
                        )
                    }
                },
            )
            put("screenshot_path", state.latestWorkspacePath)
            put("stdout", if (state.isActive) "Agent Mode display is active." else "Agent Mode display is stopped.")
        }.toString()
    }

    private fun releaseDisplay(status: String = "Virtual display stopped") {
        shizukuDisplayId?.let { displayId ->
            diagnosticLogger.event(
                category = "agent_mode",
                event = "display_released",
                details = mapOf(
                    "display_id" to displayId,
                    "method" to displayOwnerMethod?.storageValue.orEmpty(),
                    "status" to status,
                ),
            )
            when (displayOwnerMethod) {
                AgentModeAuthorizationMethod.Shizuku -> runCatching { shizukuService?.releaseDisplay(displayId) }
                AgentModeAuthorizationMethod.Root -> runCatching { rootService?.releaseDisplay(displayId) }
                null -> {
                    runCatching { shizukuService?.releaseDisplay(displayId) }
                    runCatching { rootService?.releaseDisplay(displayId) }
                }
            }
        }
        shizukuDisplayId = null
        displayOwnerMethod = null
        displayOwnerBinder = null
        _displayState.value = AgentModeDisplayState(
            isActive = false,
            displays = currentDisplaysLocal(null),
            status = status,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun currentManagedDisplayId(settings: AppSettings): Int? {
        val displayId = shizukuDisplayId ?: return null
        val service = runCatching { requireAgentModeService(settings) }
            .getOrElse { throwable ->
                if (displayOwnerMethod == settings.agentModeAuthorizationMethod) {
                    releaseDisplay(
                        throwable.message ?: "Virtual display reset because Agent Mode service is unavailable."
                    )
                }
                return null
            }
        if (isCurrentDisplayOwner(settings, service.asBinder())) {
            return displayId
        }
        releaseDisplay("Virtual display reset because Agent Mode authorization service changed.")
        return null
    }

    private fun isCurrentDisplayOwner(
        settings: AppSettings,
        binder: IBinder,
    ): Boolean =
        displayOwnerMethod == settings.agentModeAuthorizationMethod &&
            displayOwnerBinder === binder &&
            binder.isBinderAlive

    private fun clearShizukuService(displayStatus: String) {
        val handle = shizukuServiceHandle
        shizukuService = null
        shizukuServiceHandle = null
        if (displayOwnerMethod == AgentModeAuthorizationMethod.Shizuku) {
            releaseDisplay(displayStatus)
        }
        handle?.destroy()
    }

    private fun clearRootService(displayStatus: String) {
        val handle = rootServiceHandle
        rootService = null
        rootServiceHandle = null
        if (displayOwnerMethod == AgentModeAuthorizationMethod.Root) {
            releaseDisplay(displayStatus)
        }
        handle?.destroy()
    }

    private fun captureAgentModeFailed(
        settings: AppSettings,
        action: String,
        reason: String,
        message: String,
    ) {
        diagnosticLogger.event(
            category = "agent_mode",
            event = "action_failed",
            level = "warn",
            details = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message,
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
        AetherAnalytics.capture(
            event = "agent mode failed",
            properties = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message.take(280),
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
    }

    private suspend fun currentDisplays(
        settings: AppSettings,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val privilegedDisplays = runCatching {
            parseDisplays(requireAgentModeService(settings).listDisplaysJson(), aetherDisplayId)
        }.onFailure {
        }.getOrNull()
        if (privilegedDisplays != null) return privilegedDisplays
        return currentDisplaysLocal(aetherDisplayId)
    }

    private fun currentDisplaysLocal(aetherDisplayId: Int?): List<AgentModeDisplayInfo> =
        displayManager.displays.map { display ->
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            AgentModeDisplayInfo(
                displayId = display.displayId,
                name = display.name.orEmpty(),
                width = display.mode?.physicalWidth ?: size.x,
                height = display.mode?.physicalHeight ?: size.y,
                isAetherDisplay = display.displayId == aetherDisplayId ||
                    display.name.orEmpty().contains(AgentDisplayName, ignoreCase = true),
            )
        }.sortedBy { it.displayId }

    private fun parseDisplays(
        rawValue: String,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val array = org.json.JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val displayId = item.optInt("display_id")
                add(
                    AgentModeDisplayInfo(
                        displayId = displayId,
                        name = item.optString("name"),
                        width = item.optInt("width"),
                        height = item.optInt("height"),
                        isAetherDisplay = item.optBoolean("is_aether_display") ||
                            displayId == aetherDisplayId ||
                            item.optString("name").contains(AgentDisplayName, ignoreCase = true),
                    )
                )
            }
        }.sortedBy { it.displayId }
    }

    private suspend fun requireAgentModeService(settings: AppSettings): IAetherAgentModeService =
        when (settings.agentModeAuthorizationMethod) {
            AgentModeAuthorizationMethod.Shizuku -> requireShizukuService()
            AgentModeAuthorizationMethod.Root -> requireRootService()
        }

    private suspend fun requireRootService(): IAetherAgentModeService = withContext(Dispatchers.IO) {
        val existing = rootService
        if (existing != null) {
            if (existing.asBinder().isBinderAlive) {
                return@withContext existing
            }
            clearRootService("Root Agent Mode service disconnected. Virtual display was reset.")
        }
        val suPath = findSuPath()
        if (suPath.isBlank()) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "No su binary was detected on this device.",
            )
            error("No su binary was detected on this device.")
        }
        val handle = runCatching {
            AgentModeServiceLauncher.launch(context, AgentModeServiceStartTimeoutMillis) { command ->
                AgentModeServiceLauncher.startRootProcess(suPath, command)
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            diagnosticLogger.event(
                category = "agent_mode",
                event = "root_service_start_failed",
                level = "warn",
                details = mapOf("message" to throwable.message.orEmpty()),
            )
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = "Root Agent Mode service failed to start. Check that su can be granted to Aether.",
            )
            error(
                "Root Agent Mode service failed to start. Check that su can be granted to Aether. " +
                    throwable.message.orEmpty()
            )
        }
        val service = handle.service
        rootServiceHandle = handle
        rootService = service
        _authorizationState.value = AgentModeAuthorizationState(
            issue = AgentModeAuthorizationIssue.Ready,
            detail = "Root Agent Mode service is connected.",
        )
        service
    }

    @Suppress("RestrictedApi")
    private suspend fun requireShizukuService(): IAetherAgentModeService = shizukuServiceMutex.withLock {
        val existing = shizukuService
        if (existing != null) {
            if (existing.asBinder().isBinderAlive) {
                return@withLock existing
            }
            clearShizukuService("Shizuku Agent Mode service disconnected. Virtual display was reset.")
        }
        if (!Shizuku.pingBinder()) {
            error("Shizuku is not running.")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            error("Aether does not have Shizuku permission. Grant it in Shizuku first.")
        }
        diagnosticLogger.event(
            category = "agent_mode",
            event = "shizuku_service_start",
            details = mapOf("timeout_ms" to AgentModeServiceStartTimeoutMillis),
        )
        val startMillis = System.currentTimeMillis()
        val handle = try {
            AgentModeServiceLauncher.launch(
                context,
                AgentModeServiceStartTimeoutMillis,
                AgentModeServiceLauncher::startShizukuProcess,
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            diagnosticLogger.event(
                category = "agent_mode",
                event = "shizuku_service_start_failed",
                level = "warn",
                details = mapOf(
                    "duration_ms" to (System.currentTimeMillis() - startMillis),
                    "message" to throwable.message.orEmpty(),
                    "shizuku_version" to runCatching { Shizuku.getVersion() }.getOrDefault(-1),
                    "shizuku_server_patch_version" to runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1),
                ),
            )
            error(
                "Shizuku Agent Mode service failed to start: ${throwable.message.orEmpty()} " +
                    "Restart Shizuku or update Shizuku, then refresh Agent Mode status."
            )
        }
        diagnosticLogger.event(
            category = "agent_mode",
            event = "shizuku_service_started",
            details = mapOf("duration_ms" to (System.currentTimeMillis() - startMillis)),
        )
        val service = handle.service
        runCatching {
            service.asBinder().linkToDeath({
                if (shizukuServiceHandle === handle) {
                    clearShizukuService("Shizuku Agent Mode service disconnected. Virtual display was reset.")
                }
            }, 0)
        }
        shizukuServiceHandle = handle
        shizukuService = service
        service
    }

    private suspend fun inspectAuthorization(settings: AppSettings): AgentModeAuthorizationState =
        when {
            !settings.agentModeAuthorizationEnabled -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Disabled,
                detail = "Agent Mode authorization is disabled.",
            )

            settings.agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root -> inspectRootAuthorization()

            else -> inspectShizukuAuthorization()
        }

    private suspend fun inspectRootAuthorization(): AgentModeAuthorizationState = withContext(Dispatchers.IO) {
        val existing = rootService
        if (existing != null && existing.asBinder().isBinderAlive) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root Agent Mode service is already connected.",
            )
        }
        if (existing != null) {
            clearRootService("Root Agent Mode service disconnected. Virtual display was reset.")
        }

        val suPath = findSuPath()
        if (suPath.isBlank()) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "No su binary was detected on this device.",
            )
        }

        val probe = runRootAuthorizationProbe(suPath)
        when {
            probe.launchError.isNotBlank() -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = probe.launchError,
            )

            probe.exitCode == 0 -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root authorization is granted.",
            )

            probe.timedOut -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionMissing,
                detail = "Root authorization timed out. Grant su to Aether, then refresh Agent Mode status.",
            )

            else -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = probe.combinedOutput().ifBlank {
                    "Root authorization was not granted. Grant su to Aether, then refresh Agent Mode status."
                }.take(280),
            )
        }
    }

    private fun inspectShizukuAuthorization(): AgentModeAuthorizationState {
        if (!isAnyPackageInstalled(ShizukuManagerPackages)) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotInstalled,
                detail = "Install Shizuku before using Shizuku Agent Mode.",
            )
        }
        val isRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!isRunning) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Start Shizuku, then refresh Agent Mode status.",
            )
        }
        return runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.Ready,
                    detail = "Shizuku permission is granted.",
                )
            } else {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                    detail = "Grant Aether permission in Shizuku before using Agent Mode.",
                )
            }
        }.getOrElse { throwable ->
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = throwable.message ?: "Unable to inspect Shizuku permission.",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun isAnyPackageInstalled(packageNames: List<String>): Boolean =
        packageNames.any { packageName ->
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }

    private fun findSuPath(): String {
        val commonPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
        )
        commonPaths.firstOrNull { path ->
            File(path).let { it.exists() && it.canExecute() }
        }?.let { return it }

        val result = runProcess(
            command = listOf("sh", "-c", "command -v su 2>/dev/null || true"),
            timeoutMillis = RootAuthorizationProbeTimeoutMillis,
        )
        return result.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
    }

    private fun runRootAuthorizationProbe(suPath: String): RootCommandResult =
        runProcess(
            command = listOf(suPath, "-c", "true"),
            timeoutMillis = RootAuthorizationProbeTimeoutMillis,
        )

    private fun runProcess(
        command: List<String>,
        timeoutMillis: Long,
    ): RootCommandResult {
        val process = runCatching {
            ProcessBuilder(command).start()
        }.getOrElse { throwable ->
            return RootCommandResult(
                exitCode = -1,
                launchError = throwable.message.orEmpty(),
            )
        }

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                runCatching { process.destroyForcibly() }
            }
        }

        val stdout = runCatching {
            process.inputStream.bufferedReader().readText()
        }.getOrDefault("")
        val stderr = runCatching {
            process.errorStream.bufferedReader().readText()
        }.getOrDefault("")
        return RootCommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout,
            stderr = stderr,
            timedOut = !finished,
        )
    }


    private sealed interface ResolvedPoint {
        data class Valid(val x: Int, val y: Int) : ResolvedPoint
        data class Invalid(val message: String) : ResolvedPoint
    }

    private fun resolvePoint(arguments: JSONObject, xKey: String, yKey: String): ResolvedPoint {
        val state = _displayState.value
        val x = resolveAgentModeCoordinate(xKey, arguments.optDouble(xKey, Double.NaN), state.width)
        val y = resolveAgentModeCoordinate(yKey, arguments.optDouble(yKey, Double.NaN), state.height)
        return when {
            x is AgentModeCoordinateResult.OutOfRange -> ResolvedPoint.Invalid(x.message)
            y is AgentModeCoordinateResult.OutOfRange -> ResolvedPoint.Invalid(y.message)
            x is AgentModeCoordinateResult.Valid && y is AgentModeCoordinateResult.Valid ->
                ResolvedPoint.Valid(x.pixel, y.pixel)
            else -> ResolvedPoint.Invalid(
                "Both '$xKey' and '$yKey' are required, using normalized 0..$AgentModeNormalizedCoordinateMax coordinates.",
            )
        }
    }

    /** Best-effort focus diagnostics for the display; never fails the action. */
    private suspend fun focusExtras(settings: AppSettings, displayId: Int): JSONObject {
        val raw = runCatching { requireAgentModeService(settings).focusedWindowJson(displayId) }.getOrNull()
        return runCatching { JSONObject(raw.orEmpty()) }.getOrElse { JSONObject() }
    }

    // ── Perception and element-targeted actions ──────────────────────────────

    private fun actionSucceeded(action: String): JSONObject = JSONObject().apply {
        put("ok", true)
        put("action", action)
    }

    private fun stepFailure(message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("errmsg", message)
    }

    /** The reason the element channel cannot serve this display, or null when the call was served. */
    private fun JSONObject.elementsUnavailable(): String? =
        optString("elements_unavailable").takeIf { it.isNotBlank() && !optBoolean("ok") }

    /** Options handed to the privileged service for one observation. */
    private fun elementObserveOptions(arguments: JSONObject): JSONObject = JSONObject().apply {
        arguments.optString("query").takeIf { it.isNotBlank() }?.let { put("query", it) }
        arguments.optString("region").takeIf { it.isNotBlank() }?.let { put("region", it) }
        if (arguments.has("max_elements")) put("max_elements", arguments.optInt("max_elements"))
        if (arguments.has("interactive_only")) {
            put("interactive_only", arguments.optBoolean("interactive_only"))
        }
    }

    private suspend fun observeElements(
        settings: AppSettings,
        displayId: Int,
        options: JSONObject,
    ): JSONObject? {
        val raw = runCatching {
            requireAgentModeService(settings).observeElementsJson(displayId, options.toString())
        }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private suspend fun elementAction(
        settings: AppSettings,
        displayId: Int,
        request: JSONObject,
    ): JSONObject? {
        val raw = runCatching {
            requireAgentModeService(settings).elementActionJson(displayId, request.toString())
        }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private suspend fun settleElements(
        settings: AppSettings,
        displayId: Int,
        timeoutMillis: Long,
    ): JSONObject? {
        val raw = runCatching {
            requireAgentModeService(settings).settleJson(
                displayId,
                JSONObject().put("timeout_ms", timeoutMillis).toString(),
            )
        }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /**
     * Turns an action outcome into the tool result and applies the perception policy.
     *
     * Elements are the default channel and an image is attached only when the model asked for one.
     * When the element channel cannot serve this display the reason travels with the result, and
     * unless the user chose element-only perception a screenshot is attached as well: the rule this
     * implements is that a fallback has to be *stated*, not that it must never happen.
     */
    private suspend fun perceive(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        arguments: JSONObject,
        base: JSONObject,
        unavailableReason: String? = null,
        settleTimeoutMillis: Long? = null,
        delayMillis: Long = 0L,
    ): String {
        val displayId = currentManagedDisplayId(settings)
        displayId?.let { base.put("display_id", it) }
        if (unavailableReason != null) {
            base.put("elements_unavailable", unavailableReason)
        }
        if (settleTimeoutMillis != null && displayId != null && unavailableReason == null) {
            settleElements(settings, displayId, settleTimeoutMillis)
                ?.takeIf { it.optBoolean("ok") }
                ?.let { settle ->
                    base.put("settled", settle.optBoolean("settled"))
                    base.put("settle_ms", settle.optLong("elapsed_ms"))
                }
        }

        val screenshotRequested = arguments.optBoolean("screenshot", false)
        val fallbackAllowed = unavailableReason != null &&
            settings.agentModePerception == AgentModePerception.ElementsWithScreenshot
        base.put(
            "perception",
            when {
                screenshotRequested -> "screenshot"
                fallbackAllowed -> "screenshot_fallback"
                unavailableReason != null -> "elements_unavailable"
                else -> "elements"
            },
        )
        if (displayId != null && (screenshotRequested || fallbackAllowed)) {
            val fields = captureFields(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                delayMillis = if (unavailableReason == null) 0L else delayMillis,
                region = null,
                quality = AgentModeCaptureJpegQuality,
            )
            fields.keys().forEach { key ->
                if (key != "ok") base.put(key, fields.get(key))
            }
        }
        return base.toString()
    }

    /**
     * Reads the element list, or a screenshot when the element channel cannot serve the display.
     *
     * `start` and `launch` go through here rather than returning a bare status: showing the screen
     * that resulted is the whole point of both, and folding it into the same call is what keeps a task
     * from spending its first two round trips on "start" and then "look".
     */
    private suspend fun observeResult(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        arguments: JSONObject,
        settleTimeoutMillis: Long? = null,
        fallbackDelayMillis: Long = 0L,
    ): String {
        val displayId = ensureDisplay(settings)
        val base = JSONObject()
        if (settleTimeoutMillis != null) {
            settleElements(settings, displayId, settleTimeoutMillis)
                ?.takeIf { it.optBoolean("ok") }
                ?.let { settle ->
                    base.put("settled", settle.optBoolean("settled"))
                    base.put("settle_ms", settle.optLong("elapsed_ms"))
                }
        }
        val outcome = observeElements(settings, displayId, elementObserveOptions(arguments))
        val unavailableReason = outcome?.elementsUnavailable()
            ?: if (outcome == null) "service_unavailable" else null
        if (outcome != null) {
            outcome.keys().forEach { key ->
                if (key != "ok") base.put(key, outcome.get(key))
            }
        } else {
            base.put("errmsg", "The Agent Mode service did not answer the element read.")
        }
        if (unavailableReason != null && settings.agentModePerception == AgentModePerception.ElementsWithScreenshot) {
            // The read itself is served, by a screenshot; the reason travels with it.
            base.put("ok", true)
            base.remove("errmsg")
        } else {
            base.put("ok", outcome != null && outcome.optBoolean("ok"))
        }
        return perceive(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            arguments = arguments,
            base = base,
            unavailableReason = unavailableReason,
            delayMillis = fallbackDelayMillis,
        )
    }

    /**
     * Taps or long-presses an element, or a normalized coordinate when the model gave one.
     *
     * The coordinate form stays exactly as it was: it is the fallback for canvas-like screens the
     * element tree says nothing useful about, and the answer now names what the point landed on.
     */
    /** A gesture request that is ready to send, or the reason the arguments could not be turned into one. */
    private sealed interface ElementGestureRequest {
        data class Ready(
            val request: JSONObject,
            /** Set when the model gave normalized coordinates rather than an element id. */
            val devicePoint: ResolvedPoint.Valid?,
        ) : ElementGestureRequest

        data class Invalid(val message: String) : ElementGestureRequest
    }

    /**
     * Builds a tap or long-press request from either form of target.
     *
     * Shared with the batch dispatcher so a step and a direct call accept exactly the same arguments and
     * fail with exactly the same message; only the coordinate form needs the display extent, and the
     * conversion happens once, here.
     */
    private fun buildGestureRequest(source: JSONObject, action: String): ElementGestureRequest {
        val request = JSONObject().apply {
            put("kind", action)
            put("via", source.optString("via").trim().ifBlank { "touch" })
            if (source.has("duration_ms")) put("duration_ms", source.optInt("duration_ms"))
            if (source.has("settle_timeout_ms")) {
                put("settle_timeout_ms", source.optLong("settle_timeout_ms"))
            }
        }
        val targetId = source.optInt("target", source.optInt("target_id", 0))
        if (targetId > 0) {
            request.put("target_id", targetId)
            return ElementGestureRequest.Ready(request = request, devicePoint = null)
        }
        return when (val point = resolvePoint(source, "x", "y")) {
            is ResolvedPoint.Invalid -> ElementGestureRequest.Invalid(point.message)
            is ResolvedPoint.Valid -> {
                request.put("x", point.x)
                request.put("y", point.y)
                ElementGestureRequest.Ready(request = request, devicePoint = point)
            }
        }
    }

    private suspend fun gestureResult(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        arguments: JSONObject,
        longPress: Boolean,
    ): String {
        val displayId = ensureDisplay(settings)
        val action = if (longPress) "long_press" else "tap"
        val built = when (val candidate = buildGestureRequest(arguments, action)) {
            is ElementGestureRequest.Invalid -> return invalidArguments(candidate.message)
            is ElementGestureRequest.Ready -> candidate
        }
        val request = built.request
        val devicePoint = built.devicePoint
        val targetId = request.optInt("target_id", 0)

        val answered = elementAction(settings, displayId, request)
        var unavailableReason = answered?.elementsUnavailable()
        val outcome: JSONObject = if (answered != null) {
            answered
        } else {
            val point = devicePoint
            if (point == null) {
                return toolError(
                    message = "Element $targetId could not be resolved: the element channel did not answer.",
                    action = action,
                )
            }
            if (longPress) {
                // There is no long-press entry point outside the element channel, so this is reported
                // rather than downgraded to a tap the model did not ask for.
                return toolError(
                    message = "A long press at a coordinate needs the element channel, which did not answer.",
                    action = action,
                )
            }
            requireAgentModeService(settings).tap(displayId, point.x, point.y)
            unavailableReason = "service_unavailable"
            actionSucceeded(action)
        }

        val reportedX = outcome.takeIf { it.has("point_x") }?.optInt("point_x")
        val reportedY = outcome.takeIf { it.has("point_y") }?.optInt("point_y")
        when {
            reportedX != null && reportedY != null ->
                updateCursorPosition(reportedX, reportedY, animationDurationMillis = 180)

            devicePoint != null -> {
                val point = devicePoint
                updateCursorPosition(point.x, point.y, animationDurationMillis = 180)
            }
        }

        return perceive(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            arguments = arguments,
            base = outcome,
            unavailableReason = unavailableReason,
            delayMillis = 350,
        )
    }

    /**
     * Writes text and reports whether the field confirmed it.
     *
     * The element path writes through the field's own action and reads it back. Only when the element
     * channel cannot serve the display does the write fall back to the focused field and the input
     * methods that existed before, and the result says which one was used.
     */
    private suspend fun textResult(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        arguments: JSONObject,
    ): String {
        val displayId = ensureDisplay(settings)
        val text = arguments.optString("text")
        if (text.isBlank()) {
            return invalidArguments("Missing required 'text' argument.")
        }
        val targetId = arguments.optInt("target", arguments.optInt("target_id", 0))
        val outcome = elementAction(
            settings = settings,
            displayId = displayId,
            request = JSONObject().apply {
                put("kind", "set_text")
                put("text", text)
                if (targetId > 0) put("target_id", targetId)
                if (arguments.optBoolean("submit", false)) put("submit", true)
            },
        )
        if (outcome != null && outcome.elementsUnavailable() == null) {
            return perceive(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                arguments = arguments,
                base = outcome,
            )
        }

        val focus = focusExtras(settings, displayId)
        // Only refuse when the focus state was actually read and is empty; unknown focus falls through.
        if (focus.has("focused_window") && focus.optString("focused_window").isBlank()) {
            return toolError(
                message = "No window on Agent Mode display $displayId has input focus, so the text would be dropped. " +
                    "Tap the text field first, then check the screenshot for a cursor or focused field.",
                action = "text",
            )
        }
        val method = requireAgentModeService(settings).text(displayId, text)
        val base = actionSucceeded("text")
        base.put("method", method.orEmpty())
        base.put("text_verification", "unverified")
        base.put("text_verification_note", "The legacy input path does not read the field back.")
        focus.keys().forEach { key -> base.put(key, focus.get(key)) }
        return perceive(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            arguments = arguments,
            base = base,
            unavailableReason = outcome?.elementsUnavailable() ?: "service_unavailable",
            delayMillis = 350,
        )
    }

    /**
     * Scrolls one step, optionally until a piece of text shows up.
     *
     * The repeat loop lives here rather than in the model's step budget: "scroll until the entry is
     * visible" is one intention, and paying a round trip per scroll is what made long lists expensive.
     */
    private suspend fun scrollResult(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        arguments: JSONObject,
    ): String {
        val displayId = ensureDisplay(settings)
        val direction = arguments.optString("direction", "down").trim().ifBlank { "down" }
        val until = arguments.optString("until").trim()
        val maxSteps = arguments.optInt("max_steps", AgentModeDefaultScrollSteps).coerceIn(1, 30)
        val targetId = arguments.optInt("target", arguments.optInt("target_id", 0))

        var outcome: JSONObject? = null
        var steps = 0
        var reached = until.isEmpty()
        var unavailableReason: String? = null
        while (steps < maxSteps) {
            outcome = elementAction(
                settings = settings,
                displayId = displayId,
                request = JSONObject().apply {
                    put("kind", "scroll")
                    put("direction", direction)
                    if (targetId > 0) put("target_id", targetId)
                },
            )
            steps++
            unavailableReason = outcome?.elementsUnavailable()
                ?: if (outcome == null) "service_unavailable" else null
            if (until.isEmpty() || unavailableReason != null) break
            val observation = observeElements(settings, displayId, JSONObject().put("query", until))
            if (observation != null && observation.optInt("total") > 0) {
                reached = true
                break
            }
            if (outcome?.optBoolean("ok") != true) break
            delay(AgentModeScrollStepDelayMillis)
        }

        val base = outcome ?: stepFailure("The scroll request did not answer.")
        base.put("action", "scroll")
        base.put("direction", direction)
        base.put("steps", steps)
        if (until.isNotEmpty()) {
            base.put("until", until)
            base.put("until_found", reached)
            if (!reached && unavailableReason == null) {
                base.put("ok", false)
                base.put("errmsg", "Scrolled $steps step(s) without finding \"$until\".")
            }
        }
        return perceive(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            arguments = arguments,
            base = base,
            unavailableReason = unavailableReason,
        )
    }

    /**
     * Waits for a condition, and says whether it was met.
     *
     * `until` accepts `settle` (the default), `text:<s>`, `gone:<s>` and `ms:<n>`. A wait that timed
     * out reports that it timed out instead of returning success because time passed.
     */
    /**
     * Polls until a text condition holds.
     *
     * Only text conditions are offered. Every action already waits for the screen to stop changing, so a
     * "wait for settle" mode would only duplicate what the result reports, and an explicit sleep is not
     * something a UI tool needs to provide. The same poller answers a batch wait step, so both forms of
     * the condition mean exactly the same thing.
     */
    private suspend fun awaitTextCondition(
        settings: AppSettings,
        displayId: Int,
        until: String,
        timeoutMillis: Int,
    ): JSONObject {
        val gone = until.startsWith("gone:")
        val needle = until.removePrefix(if (gone) "gone:" else "text:")
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutMillis
        var unavailableReason: String? = null
        while (true) {
            val observation = observeElements(settings, displayId, JSONObject().put("query", needle))
            unavailableReason = observation?.elementsUnavailable()
                ?: if (observation == null) "service_unavailable" else null
            if (observation != null && (observation.optInt("total") > 0) != gone) {
                return JSONObject().apply {
                    put("ok", true)
                    put("condition", until)
                    put("satisfied", true)
                    put("elapsed_ms", System.currentTimeMillis() - startedAt)
                }
            }
            if (unavailableReason != null || System.currentTimeMillis() >= deadline) break
            delay(AgentModeWaitPollIntervalMillis)
        }
        return JSONObject().apply {
            put("ok", false)
            put("condition", until)
            put("satisfied", false)
            put("elapsed_ms", System.currentTimeMillis() - startedAt)
            if (unavailableReason != null) {
                put("elements_unavailable", unavailableReason)
            } else {
                put("errmsg", "Wait condition '$until' was not met within $timeoutMillis ms.")
            }
        }
    }

    private suspend fun waitResult(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        arguments: JSONObject,
    ): String {
        val displayId = ensureDisplay(settings)
        val until = arguments.optString("until").trim()
        if (!until.startsWith("text:") && !until.startsWith("gone:")) {
            return invalidArguments(
                "'until' must be \"text:<s>\" or \"gone:<s>\"; actions already wait for the screen to settle.",
            )
        }
        val timeoutMillis = arguments.optInt("timeout_ms", AgentModeDefaultWaitTimeoutMillis)
            .coerceIn(0, AgentModeMaxWaitTimeoutMillis)
        val base = awaitTextCondition(settings, displayId, until, timeoutMillis)
        base.put("action", "wait")
        return perceive(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            arguments = arguments,
            base = base,
            unavailableReason = base.optString("elements_unavailable").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Runs a short sequence of steps in one call.
     *
     * Every step is validated before the first one runs, so a typo in a later entry cannot leave the
     * device half-way through a sequence, and only a per-step summary plus the final observation come
     * back, which is what keeps a multi-step interaction from costing a round trip per step.
     */
    private suspend fun batchResult(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        arguments: JSONObject,
    ): String {
        val steps = arguments.optJSONArray("steps")
            ?: return invalidArguments("'steps' must be an array of agent_display calls.")
        if (steps.length() == 0) {
            return invalidArguments("'steps' must not be empty.")
        }
        if (steps.length() > AgentModeMaxBatchSteps) {
            return invalidArguments("'steps' may hold at most $AgentModeMaxBatchSteps entries.")
        }
        val parsed = mutableListOf<JSONObject>()
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index)
                ?: return invalidArguments("Step $index is not an object.")
            val stepAction = step.optString("action").trim().lowercase()
            if (stepAction.isBlank()) {
                return invalidArguments("Step $index is missing 'action'.")
            }
            if (stepAction !in AgentModeBatchStepActions) {
                return invalidArguments(
                    "Step $index uses unsupported action '$stepAction'. Supported: " +
                        AgentModeBatchStepActions.joinToString(", ") + ".",
                )
            }
            parsed += step
        }

        val report = arguments.optString("report", "final").trim().lowercase()
        val stopOnError = arguments.optBoolean("stop_on_error", true)
        val summaries = JSONArray()
        val observations = JSONArray()
        var failedIndex = -1
        var failureMessage = ""
        var finalObservation: String? = null

        for ((index, step) in parsed.withIndex()) {
            val stepAction = step.optString("action").trim().lowercase()
            val startedAt = System.currentTimeMillis()
            val result = runCatching { executeBatchStep(settings, step, stepAction) }
                .getOrElse { throwable ->
                    stepFailure(throwable.message ?: throwable.javaClass.simpleName)
                }
            val ok = result.optBoolean("ok")
            summaries.put(
                JSONObject().apply {
                    put("index", index)
                    put("action", stepAction)
                    put("ok", ok)
                    put("ms", System.currentTimeMillis() - startedAt)
                    if (!ok) put("errmsg", result.optString("errmsg").take(200))
                },
            )
            if (ok && stepAction == "observe") {
                when (report) {
                    "each" -> observations.put(result.optString("stdout"))
                    "none" -> Unit
                    else -> finalObservation = result.optString("stdout").takeIf { it.isNotBlank() }
                }
            }
            if (!ok) {
                failedIndex = index
                failureMessage = result.optString("errmsg")
                if (stopOnError) break
            }
        }

        val base = JSONObject().apply {
            put("ok", failedIndex < 0)
            put("action", "batch")
            put("step_count", parsed.size)
            put("steps", summaries)
            if (failedIndex >= 0) {
                put("failed_step", failedIndex)
                put("errmsg", failureMessage)
            }
            when (report) {
                "each" -> put("observations", observations)
                "none" -> Unit
                else -> finalObservation?.let { put("stdout", it) }
            }
        }
        return perceive(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            arguments = arguments,
            base = base,
        )
    }

    private suspend fun executeBatchStep(
        settings: AppSettings,
        step: JSONObject,
        action: String,
    ): JSONObject {
        val displayId = ensureDisplay(settings)
        return when (action) {
            "observe" -> observeElements(settings, displayId, elementObserveOptions(step))
                ?: stepFailure("The element read did not answer.")

            "tap", "long_press" -> when (val built = buildGestureRequest(step, action)) {
                is ElementGestureRequest.Invalid -> stepFailure(built.message)
                is ElementGestureRequest.Ready ->
                    elementAction(settings, displayId, built.request)
                        ?: stepFailure("The gesture did not answer.")
            }

            "text" -> elementAction(
                settings = settings,
                displayId = displayId,
                request = JSONObject().apply {
                    put("kind", "set_text")
                    put("text", step.optString("text"))
                    step.optInt("target", step.optInt("target_id", 0))
                        .takeIf { it > 0 }
                        ?.let { put("target_id", it) }
                    if (step.optBoolean("submit", false)) put("submit", true)
                },
            ) ?: stepFailure("The text write did not answer.")

            "scroll" -> elementAction(
                settings = settings,
                displayId = displayId,
                request = JSONObject().apply {
                    put("kind", "scroll")
                    put("direction", step.optString("direction", "down").trim().ifBlank { "down" })
                    step.optInt("target", step.optInt("target_id", 0))
                        .takeIf { it > 0 }
                        ?.let { put("target_id", it) }
                },
            ) ?: stepFailure("The scroll did not answer.")

            "key" -> {
                val keyCode = step.optString("key").trim()
                if (keyCode.isBlank()) {
                    stepFailure("'key' is required for a batch key step.")
                } else {
                    requireAgentModeService(settings).key(displayId, keyCode)
                    actionSucceeded("key")
                }
            }

            "swipe" -> {
                val start = resolvePoint(step, "x1", "y1")
                val end = resolvePoint(step, "x2", "y2")
                if (start !is ResolvedPoint.Valid || end !is ResolvedPoint.Valid) {
                    stepFailure("A batch swipe needs x1, y1, x2 and y2.")
                } else {
                    requireAgentModeService(settings).swipe(
                        displayId,
                        start.x,
                        start.y,
                        end.x,
                        end.y,
                        step.optInt("duration_ms", 500).coerceIn(50, 10_000),
                    )
                    actionSucceeded("swipe")
                }
            }

            "launch" -> {
                val target = step.optString("target").trim()
                if (target.isBlank()) {
                    stepFailure("'target' is required for a batch launch step.")
                } else {
                    launchTarget(settings, target)
                    actionSucceeded("launch")
                }
            }

            "wait" -> {
                val until = step.optString("until").trim()
                if (!until.startsWith("text:") && !until.startsWith("gone:")) {
                    stepFailure("A batch wait needs until=\"text:<s>\" or \"gone:<s>\".")
                } else {
                    awaitTextCondition(
                        settings = settings,
                        displayId = displayId,
                        until = until,
                        timeoutMillis = step.optInt("timeout_ms", AgentModeDefaultWaitTimeoutMillis)
                            .coerceIn(0, AgentModeMaxWaitTimeoutMillis),
                    ).apply { put("action", "wait") }
                }
            }

            else -> stepFailure("Unsupported batch action '$action'.")
        }
    }

    /**
     * Captures the display, optionally one rectangle of it.
     *
     * A screenshot is an explicit request, so the perception policy does not apply: the model asked for
     * an image and gets one. A cropped capture reports its own origin and scale, which is what turns a
     * pixel read off the image into a device coordinate without guessing at a downscale factor.
     */
    private suspend fun screenshotResult(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        arguments: JSONObject,
    ): String {
        ensureDisplay(settings)
        val rawRegion = arguments.optString("region").trim()
        val region = if (rawRegion.isBlank()) null else parseRegionArgument(rawRegion)
        if (rawRegion.isNotBlank() && region == null) {
            return invalidArguments(
                "'region' must be \"left,top,right,bottom\" inside the display, e.g. \"0,0,540,600\".",
            )
        }
        val fields = captureFields(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = termuxWorkspaceDirectory,
            delayMillis = 0L,
            region = region,
            quality = if (region == null) AgentModeCaptureJpegQuality else AgentModeCaptureRegionJpegQuality,
        )
        val base = actionSucceeded("screenshot")
        fields.keys().forEach { key ->
            if (key != "ok") base.put(key, fields.get(key))
        }
        base.put("perception", "screenshot")
        return base.toString()
    }

    private fun parseRegionArgument(rawValue: String): Rect? {
        val parts = rawValue.split(',').map { it.trim() }
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        val state = _displayState.value
        if (state.width <= 0 || state.height <= 0) return null
        val region = Rect(values[0], values[1], values[2], values[3])
        if (region.width() <= 0 || region.height() <= 0) return null
        if (region.left < 0 || region.top < 0) return null
        if (region.right > state.width || region.bottom > state.height) return null
        return region
    }

    private fun invalidArguments(message: String): String =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", message)
        }.toString()

    private fun toolError(
        message: String,
        action: String,
    ): String = JSONObject().apply {
        put("ok", false)
        put("action", action)
        put("errmsg", message)
        put("stdout", "")
    }.toString()

    private fun currentDeviceDisplaySpec(): DisplaySpec {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        val metrics = context.resources.displayMetrics
        return DisplaySpec(
            width = (display?.mode?.physicalWidth ?: size.x).takeIf { it > 0 }
                ?: metrics.widthPixels.takeIf { it > 0 }
                ?: FallbackAgentDisplayWidth,
            height = (display?.mode?.physicalHeight ?: size.y).takeIf { it > 0 }
                ?: metrics.heightPixels.takeIf { it > 0 }
                ?: FallbackAgentDisplayHeight,
            densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: FallbackAgentDisplayDensityDpi,
        )
    }

    private data class DisplaySpec(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    private data class RootCommandResult(
        val exitCode: Int,
        val stdout: String = "",
        val stderr: String = "",
        val timedOut: Boolean = false,
        val launchError: String = "",
    ) {
        fun combinedOutput(): String = listOf(stdout, stderr, launchError)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
    }
}
