# Runtime Permission Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Request Android runtime permissions only when the related capability is used, then resume the original action after authorization.

**Architecture:** Keep Android permission launchers in `MainActivity`, add a pure classifier for queries that need current location or calendar access, and pass a send callback from the chat UI so the activity can preflight a send before the ViewModel starts it. Store one pending action while a system dialog is open; a successful result executes it once, while denial leaves the feature usable and offers app settings when another dialog is unlikely to appear.

**Tech Stack:** Kotlin, Jetpack Compose, Activity Result APIs, Android `PackageManager`, JUnit, existing Compose Android tests.

---

### Task 1: Add pure capability classification

**Files:**
- Create: `app/src/main/java/com/example/agentchat/data/permission/PermissionRequirement.kt`
- Test: `app/src/test/java/com/example/agentchat/data/permission/PermissionRequirementTest.kt`

- [ ] **Step 1: Write the failing tests**

Add tests for these exact behaviors:

```kotlin
class PermissionRequirementTest {
    @Test fun cityWeatherDoesNotNeedDeviceLocation() {
        assertFalse(PermissionRequirement.needsCurrentLocation("北京今天天气怎么样"))
    }

    @Test fun currentWeatherNeedsDeviceLocation() {
        assertTrue(PermissionRequirement.needsCurrentLocation("查一下当前位置天气"))
    }

    @Test fun calendarQuestionNeedsReadPermission() {
        assertTrue(PermissionRequirement.needsCalendarRead("我今天有什么会议"))
    }

    @Test fun ordinaryChatNeedsNoDevicePermission() {
        assertFalse(PermissionRequirement.needsCurrentLocation("帮我润色这段话"))
        assertFalse(PermissionRequirement.needsCalendarRead("解释一下 RAG"))
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest --tests '*PermissionRequirementTest'
```

Expected: compilation fails because `PermissionRequirement` does not yet exist.

- [ ] **Step 3: Implement the minimal classifier**

Create an Android-independent object with weather/calendar keyword lists. Treat a weather query as current-location dependent only when it contains a weather term and does not match either explicit-city regex already used by `WebSearchClient`.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same Gradle command. Expected: all four tests pass.

- [ ] **Step 5: Commit the isolated unit**

```bash
git add app/src/main/java/com/example/agentchat/data/permission/PermissionRequirement.kt app/src/test/java/com/example/agentchat/data/permission/PermissionRequirementTest.kt
git commit -m "test(android): classify permission-dependent requests"
```

### Task 2: Add pending-action permission orchestration

**Files:**
- Modify: `app/src/main/java/com/example/agentchat/MainActivity.kt`
- Create: `app/src/main/java/com/example/agentchat/data/permission/PermissionRequestState.kt`
- Test: `app/src/test/java/com/example/agentchat/data/permission/PermissionRequestStateTest.kt`

- [ ] **Step 1: Write the failing state tests**

Test a one-shot pending action: setting an action returns it once after a successful permission result, denial clears it, and a second callback cannot replay it.

- [ ] **Step 2: Run the focused test and verify it fails**

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest --tests '*PermissionRequestStateTest'
```

Expected: compilation fails because the state type does not exist.

- [ ] **Step 3: Implement the state holder and activity helpers**

Add a small state holder for `PendingPermissionAction` values (`SendMessage`, `StartVoice`, `CreateCalendarEvent`) and wire `MainActivity` to:

- check missing permissions with `checkSelfPermission`;
- launch the existing `RequestPermission`/`RequestMultiplePermissions` launchers;
- execute the stored action only when the required result is granted;
- clear the stored action on denial or cancellation;
- open `ACTION_APPLICATION_DETAILS_SETTINGS` for a denied permission when `shouldShowRequestPermissionRationale` is false after a request attempt;
- keep the existing location-service settings intent separate from app permission settings.

Do not request permissions from the startup `LaunchedEffect`. Keep the `ON_RESUME` observer to refresh visible location state.

- [ ] **Step 4: Run the focused tests and compile**

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest --tests '*PermissionRequestStateTest' compileDebugKotlin
```

Expected: tests pass and Kotlin compilation succeeds.

- [ ] **Step 5: Commit the orchestration unit**

```bash
git add app/src/main/java/com/example/agentchat/MainActivity.kt app/src/main/java/com/example/agentchat/data/permission/PermissionRequestState.kt app/src/test/java/com/example/agentchat/data/permission/PermissionRequestStateTest.kt
git commit -m "feat(android): orchestrate just-in-time permissions"
```

### Task 3: Route chat, voice, and calendar actions through the preflight

**Files:**
- Modify: `app/src/main/java/com/example/agentchat/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/example/agentchat/MainActivity.kt`
- Modify: `app/src/main/java/com/example/agentchat/ui/chat/ChatViewModel.kt` only if a protected action needs a public query helper

- [ ] **Step 1: Add a UI send callback without changing existing test defaults**

Add `onSend: () -> Unit = { onIntent(ChatIntent.Send) }` to `ChatScreen` and `ChatScreenContent`; make the composer call `onSend` when it is not streaming. Existing callers that omit the callback keep the current behavior.

- [ ] **Step 2: Wire location and calendar preflight in `MainActivity`**

Pass a callback that:

- classifies the current draft with `PermissionRequirement`;
- requests location before a current-location weather send;
- requests calendar read before a calendar-context send;
- otherwise calls `container.chatViewModel.onIntent(ChatIntent.Send)` immediately;
- resumes the send once after the launcher result.

Keep explicit-city weather requests independent of location permission.

- [ ] **Step 3: Preserve just-in-time voice behavior**

Keep `startVoice` as the voice preflight. On grant it starts `VoiceInputController`; on denial it reports the existing text-input fallback and exposes the settings route for permanent denial.

- [ ] **Step 4: Harden calendar confirmation**

Keep requesting calendar write permission only from `onCalendarConfirm`. Require `WRITE_CALENDAR` for insertion, accept `READ_CALENDAR` as an additional requested permission when missing, and preserve the pending draft on denial so the user can retry after changing settings.

- [ ] **Step 5: Add/adjust Compose tests**

Add test coverage that the chat invokes `onSend` instead of directly sending, and retain existing tests for voice and calendar confirmation. The activity-level launcher result behavior is covered by the pure pending-action tests and a smoke test that verifies no permission launcher is triggered at startup.

### Task 4: Handle notification permission at the background-notification boundary

**Files:**
- Modify: `app/src/main/java/com/example/agentchat/MainActivity.kt`
- Modify: `app/src/main/java/com/example/agentchat/data/background/AgentTurnForegroundService.kt`

- [ ] **Step 1: Use the existing completion-notification boundary**

Use `ForegroundAgentTurnController.onTurnFinished` in `AgentTurnForegroundService.kt`, where `postCompletionNotification` is called only when the activity is no longer visible. Do not add a prompt to app startup or ordinary foreground chat.

- [ ] **Step 2: Request `POST_NOTIFICATIONS` only at that boundary**

On Android 13+, if notification permission is missing, launch the permission request and continue the agent turn regardless of the result. On older Android versions, skip the runtime request. A denied result must not fail or cancel the model request.

- [ ] **Step 3: Add a regression test or smoke assertion**

Verify the startup composition does not launch notification permission and that notification denial leaves the turn path non-failing.

### Task 5: Full verification and documentation

**Files:**
- Modify: `CHANGELOG.md` only if the project release process requires an entry for this change.

- [ ] **Step 1: Run unit tests**

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest
```

Expected: exit code 0 with no failed tests.

- [ ] **Step 2: Run lint and assemble the debug APK**

```bash
./gradlew --no-daemon --max-workers=1 compileDebugKotlin lintDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`; existing compile-SDK compatibility warnings may remain, but no new errors or lint failures.

- [ ] **Step 3: Inspect the final diff**

```bash
git diff --check origin/main..HEAD
git diff --stat origin/main..HEAD
git status --short --branch
```

Confirm only permission-flow source, tests, design/plan docs, and intentional release notes are committed; do not add `app/build`, APK outputs, heap dumps, or temporary files.

- [ ] **Step 4: Report the APK and test evidence**

Use the generated path `app/build/outputs/apk/debug/app-debug.apk` and include the exact successful Gradle commands in the handoff.
