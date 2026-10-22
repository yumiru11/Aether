# Phone Use

Phone Use is Android device control through a user-enabled Accessibility service. The default mode operates the foreground screen without Root or Shizuku. The default input strategy returns element trees from `start`/`observe` and after actions. Screenshot-first instead returns images. The default output strategy prefers element operations; coordinate gestures can be selected instead. Foreground control has no live-preview or replay card.

Virtual Display is an optional background mode. Root or Shizuku creates and launches apps on the display; `PhoneUseAccessibilityService` reads and operates its windows using the display ID. The existing card streams the display surface. The privileged Binder service provides display lifecycle and app discovery/launch only, with no input injection or shell-command interface. Its off-screen ImageReader is drained when a preview is absent so rendering continues.

## Setup and compatibility

Phone Use defaults to enabled. App launch/resume never presents an Accessibility permission dialog. Explicitly selecting Phone Use from the composer plus menu checks permission and requests it when missing; manually enabling Phone Use in Settings also requests it. Android requires the user to enable **Aether** in system Accessibility settings. Force-stop and some OEM background policies can suspend the service independently of this app preference.

Settings → Phone Use contains only the enable toggle, input and output strategy dropdowns, the allow-alternative-strategy toggle (default on), and Enable Virtual Display (experimental, default off). Enabling Virtual Display opens a secondary Root/Shizuku authorization page. The preference is enabled only after authorization succeeds. Root authorization does not configure Termux or change its files.

Follow-up onboarding only offers Termux setup. Phone Use is absent from onboarding; Root automatic configuration has been removed.

Foreground element actions and gestures work from the existing minimum Android version. Accessibility screenshots and display-scoped background control require Android 11 (API 30). Secure windows cannot be captured. Global Back/Home/Recents actions cannot be scoped to a background display, so Virtual Display refuses them; use app navigation elements instead. IME Enter uses the focused field's accessibility action on API 30 and later.

Phone Use tasks keep the existing task notification/foreground service active while running, including when the general background preference is off, because launching a controlled app puts Aether in the background. Ordinary chats continue to respect that preference. Scheduled tasks can opt into Phone Use per task in Settings → Scheduled Tasks (or via `phone_use` on `aether_scheduled_task_manage`); the triggered run requests Phone Use and starts the foreground service even when the general background preference is off. Foreground Phone Use still needs the screen on and unlocked; use Virtual Display for unattended background runs.

A one-time upgrade migration enables foreground Phone Use and disables Virtual Display. Subsequent explicit off choices persist. Existing selected Root/Shizuku providers are remembered for the secondary authorization page, and saved chat tool selections/imports remain compatible. Storage field names and the host tool identifiers `agent_display` and `aether_agent_mode_manage` remain compatible with old chats and extensions.

iOS keeps device control unavailable: its public accessibility APIs do not let third-party apps read or operate other apps. Shared labels use Phone Use, and the native iOS settings/composer continue to omit this unsupported capability.

## Foreground feedback and Steer (Android only)

During a running foreground Phone Use turn, leaving Aether displays an animated edge glow and a pale bottom commentary capsule. Returning to Aether, locking the screen, stopping the turn, disabling Phone Use, or enabling Virtual Display removes the feedback. The commentary and Steer capsule uses continuous corners; short text is vertically centered even at the minimum capsule height. Keyboard avoidance uses the Accessibility input-method window’s display-space bounds (not the small overlay’s often-zero IME Insets) and animates the capsule above that boundary. The border follows physical display corners and uses a bottom-up activation bloom before settling into a narrow moving color band. System animation preferences are respected.

The capsule displays the latest public assistant Text block, retains it across tool calls/status updates/Steer checkpoints, and replaces it when the next commentary begins. Reasoning and transport status are never substituted for commentary. New text rises and sharpens while streaming; the capsule grows with up to four visible lines, following the newest lines in long replies. Chinese, emoji and unbroken words use Android text layout.

Double-tapping the capsule opens a keyboard and inline Steer field above the controlled app. Send uses the conversation's existing non-cancelling Steer queue, pinned to the session and run the user opened; it cannot steer a replacement execution. Cancel returns to the commentary. No Activity is launched and no task is paused. Only the capsule accepts touch; the edge passes all input through. Agent coordinate gestures temporarily make the capsule pass-through too. For global navigation or coordinates covered by the Steer keyboard, the IME briefly yields and then returns with the draft intact; element actions do not need this handoff. Feedback windows and the Steer keyboard are excluded from element observations, and feedback is transparent during screenshot capture. The screenshot still reflects the real foreground screen, including any visible system keyboard.

## Host tool contract

- `observe`/`start`: observe using the configured input strategy. `tree` and `screenshot`, or `input_strategy`, explicitly select the alternative for one action when switching is allowed. `node_id` is local to the latest observation. Each window also exposes a `content_hash` excluding node IDs and a count of events since the preceding observation; event counts do not establish that the observed content actually changed. Bounds are display pixels; gesture coordinates are normalized to 0–1000.
- `tap`/`click`, `long_click`, `text`, `scroll`: use `node_id` for element operations. Output preference chooses the target when both node and coordinates are supplied. Alternative operations are rejected when strategy switching is disabled; text entry still uses Accessibility editable-field actions. Text replaces the field value and supports empty text and Unicode. Password text and descriptions are redacted.
- `tap`/`long_click` without `node_id`, and `swipe`: dispatch Accessibility gestures to the selected display. Gesture cancellation is reported as an error.
- `screenshot`: return an image through the existing Pi tool image transport and save it to the workspace. Foreground screenshots are excluded from Virtual Display replay cards.
- `stop` optionally accepts `return_to_aether: true` in foreground mode. This ends Phone Use and brings the installed Aether app back to the foreground through its bound Accessibility service. `returned_to_aether` is true only after the foreground window is confirmed; a blocked launch reports failure. Omitting the flag (or false) leaves the operated app visible. The Agent chooses based on whether the user should return to the conversation or keep viewing the result. Virtual Display rejects this option.
- `launch`, `list_apps`, `status`, `key`, `stop`: retain their existing roles. Supported foreground navigation keys are Back, Home, Recents, Notifications, and Quick Settings; Enter targets the focused input.

Observations are bounded to 1,200 nodes and 48 levels. An explicit new observation or completed action invalidates old element IDs; unrelated content events do not. Foreground updates do not invalidate a background display's observation; window membership and node display IDs prevent cross-display retargeting. Actions are serialized across chats. Revoking Accessibility or disabling Phone Use releases an active Virtual Display.

## Virtual Display orientation and fullscreen preview

Android Virtual Display supports **Device default**, **Portrait**, and **Landscape** in Settings → Phone Use when Virtual Display is enabled. Device default preserves the physical display dimensions; portrait/landscape order the short/long edges. Changing the saved choice stops the managed display; relaunch the app afterwards. Foreground Accessibility uses the actual screen geometry and does not need this setting. iOS has no Android Virtual Display implementation; shared settings and data archives preserve the preference.

`agent_display` accepts a session-only `orientation` (`device`, `portrait`, `landscape`) on `start`/`launch` in Virtual Display mode:

```json
{"action":"launch","target":"com.miniclip.plagueinc","orientation":"landscape"}
```

The override persists for subsequent observations/actions until stop, without changing the saved preference. Switching orientation recreates the display; follow `start` with `launch`. Invalid requests and failed display creation do not retain a new override. Foreground requests reject this parameter. `status` reports the selected orientation in Virtual Display mode. Configuration tools expose the saved choice as `agent_mode.display_orientation`.

The display and its off-screen buffer use the same dimensions. Preview surfaces are recreated when the display ID or geometry changes, and input continues through Accessibility on that display with normalized 0–1000 gesture coordinates.

Tap the preview frame to reveal **Fullscreen**. The viewer fits both portrait and landscape content without stretching or cropping; Android Back or **Exit fullscreen** returns to the card. Only one live surface is composed at a time. Leaving Aether dismisses fullscreen, and fullscreen is not restored on reopening.

Hardware checks for both Root and Shizuku: launch a landscape-only app; confirm landscape observations/screenshots, fitted preview, and aligned element/gesture input. Switch orientation and relaunch; verify session override persistence, stop restoring the saved preference, screenshot capture while previewing, fullscreen enter/exit and device rotation. Restart Aether and export/import settings to verify persistence.

## Dynamic UI and element identity

Do not wait for the entire screen to become idle. Status-bar clocks, loading indicators, feeds, animations and background windows may keep producing Accessibility events while the intended control remains unchanged. These events are recorded per window for diagnostics; they do not invalidate every node.

Immediately before an element action, the service confirms that its window still exists on the intended display and belongs to the same app. A different focused window blocks an underlying application target, while an input-method window does not. It refreshes the specific node and verifies its semantic identity (package, class, resource ID, labels/hints, editable/password/checkable roles, input type and nearby ancestor context), visibility and action support. Editable values, focus and screen coordinates are mutable state, so ordinary input updates or layout movement do not expire an unchanged control.

If the original Android node was rebuilt, the service searches only its original live window for an exact and unique semantic match. The search is bounded to the same 1,200-node/48-level limits as observation. Missing, ambiguous or truncated results fail; there is no nearest-coordinate/index selection. An action is submitted once, and success returns a fresh observation plus `target_resolution`. The host does not automatically repeat a submitted action.

Recoverable failures return `error_code`, `action_succeeded=false`, and a fresh `observation` when input-strategy preferences permit it. The Agent can choose a new actionable target directly from that result. If the target remains inaccessible, an allowed alternative must be invoked through a real screenshot/gesture tool call; writing simulated tool-result text is never evidence of execution.

In the observed Chrome reproduction, three consecutive Chrome snapshots were identical after removing ephemeral IDs; System UI snapshots changed. Two attempted taps targeted the unchanged visible search input, and another targeted an invisible URL field. The previous whole-tree event invalidation rejected all three before target validation.

## Verification

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :shared:testDebugUnitTest --no-daemon`. Robolectric service tests cover element-only observations, window isolation, password redaction, Unicode/empty text, stale IDs, navigation isolation, and gesture display IDs/completion/cancellation. Dynamic UI tests cover continuous content events, semantic changes, hidden targets, window/package changes, focused dialogs, keyboard windows, unique rebinding, and ambiguous replacements. Additional tests cover preference/archive compatibility, permission prompt policy, strategy selection and enforcement, and foreground-service lifetime.

Connected-device validation must enable the service through system settings, verify foreground control and screenshot fallback in real apps, and test both privileged Virtual Display providers with live preview. Unit tests do not establish OEM behavior or Root/Shizuku device compatibility.

Element-tree Binder reads, hashing and JSON construction run off the main thread; only snapshot setup and publishing run on it. Interrupted or cancelled observations cannot publish stale elements, and unfinished node copies are recycled. Foreground feedback prepares shader and blur resources before leaving Aether, renders only the screen-edge bands without an extra full-screen hardware layer, and begins activation timing with the first drawn frame. The Phone Use plus-menu action checks live Accessibility permission before toggling: revoked access prompts again even in a previously selected conversation after a cancelled reply. Removing the composer chip still deselects without a permission prompt.
