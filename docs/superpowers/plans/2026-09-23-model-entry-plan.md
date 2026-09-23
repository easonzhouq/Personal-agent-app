# Model Entry Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a prominent first-run model button, a header model switcher, and a reusable third-party LLM configuration dialog while preserving the existing provider and secret-storage behavior.

**Architecture:** Keep chat-page state in `MainActivity` and make model configuration a dialog overlay. `ChatScreenContent` renders either the empty-state CTA or the enabled-model dropdown. `ModelConfigViewModel` exposes the ID of the most recently saved configuration so the activity can select it, close the dialog, and return to chat without duplicating repository logic.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android instrumented Compose tests, Room-backed `ModelConfigRepository`, existing `ModelConfigViewModel` and `ProviderRegistry`.

---

## File Map

- Modify `app/src/main/java/com/example/agentchat/ui/chat/ChatScreen.kt`: render the no-model CTA and configured-model header/menu.
- Modify `app/src/main/java/com/example/agentchat/ui/modelconfig/ModelConfigViewModel.kt`: expose a one-shot saved-config ID for dialog completion.
- Modify `app/src/main/java/com/example/agentchat/ui/modelconfig/ModelConfigScreen.kt`: support the dialog layout without changing the existing full-screen configuration behavior.
- Create `app/src/main/java/com/example/agentchat/ui/modelconfig/ModelConfigDialog.kt`: provide the modal surface and reuse `ModelConfigScreen`.
- Modify `app/src/main/java/com/example/agentchat/MainActivity.kt`: open/reset the dialog from both entry points and select/close after a successful save.
- Modify `app/src/androidTest/java/com/example/agentchat/ui/chat/ChatScreenTest.kt`: cover the empty CTA and the add-new-model menu item.
- Modify `app/src/test/java/com/example/agentchat/ui/modelconfig/ModelConfigViewModelTest.kt`: cover the saved-config completion signal.

## Task 1: Add failing UI and ViewModel tests

**Files:**
- Modify: `app/src/androidTest/java/com/example/agentchat/ui/chat/ChatScreenTest.kt`
- Modify: `app/src/test/java/com/example/agentchat/ui/modelconfig/ModelConfigViewModelTest.kt`

- [ ] **Step 1: Add the empty-state CTA test.** Add this test to `ChatScreenTest`:

```kotlin
@Test
fun emptyModelListShowsLargeAddModelCta() {
    var addClicked = false

    compose.setContent {
        ChatScreenContent(
            state = ChatUiState(),
            onIntent = {},
            availableModels = emptyList(),
            onAddModelClick = { addClicked = true },
        )
    }

    compose.onNodeWithTag("add-model-empty-state").assertIsDisplayed().performClick()
    assertTrue(addClicked)
}
```

Add `import org.junit.Assert.assertTrue` if it is not already present.

- [ ] **Step 2: Add the configured-menu add-item test.** Add this test to `ChatScreenTest`:

```kotlin
@Test
fun modelSwitcherLastItemOpensAddModelFlow() {
    val model = ModelConfig(
        id = "model-1",
        displayName = "GPT-4o",
        baseUrl = "https://example.com/v1",
        modelName = "gpt-4o",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
    )
    var addClicked = false

    compose.setContent {
        ChatScreenContent(
            state = ChatUiState(selectedModel = model),
            onIntent = {},
            availableModels = listOf(model),
            onAddModelClick = { addClicked = true },
        )
    }

    compose.onNodeWithTag("model-selector").performClick()
    compose.onNodeWithTag("add-model-menu-item").assertIsDisplayed().performClick()
    assertTrue(addClicked)
}
```

- [ ] **Step 3: Add the saved-config signal test.** Add this test to `ModelConfigViewModelTest`:

```kotlin
@Test
fun successfulSavePublishesSavedConfigId() = runTest {
    val repository = FakeModelConfigRepository()
    val viewModel = ModelConfigViewModel(repository, FakeSecretStore(), dispatcher)

    viewModel.updateDisplayName("OpenAI")
    viewModel.updateBaseUrl("https://api.example.com")
    viewModel.updateModelName("gpt-4o")
    viewModel.save(makeDefault = true)
    advanceUntilIdle()

    assertEquals(repository.saved.single().id, viewModel.uiState.value.lastSavedConfigId)
}
```

- [ ] **Step 4: Run the focused tests and verify they fail for missing behavior.**

Run from the Android Studio Terminal in `/Users/shmizhouyicheng/Documents/Codex/2026-09-10/new-chat/android-agent`:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.agentchat.ui.modelconfig.ModelConfigViewModelTest.successfulSavePublishesSavedConfigId
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.agentchat.ui.chat.ChatScreenTest
```

Expected: the ViewModel test fails because `lastSavedConfigId` is not yet exposed, and the instrumentation tests fail because the new test tags/CTA are not yet rendered. Do not change production code before observing these failures.

## Task 2: Implement the save completion signal and dialog shell

**Files:**
- Modify: `app/src/main/java/com/example/agentchat/ui/modelconfig/ModelConfigViewModel.kt`
- Modify: `app/src/main/java/com/example/agentchat/ui/modelconfig/ModelConfigScreen.kt`
- Create: `app/src/main/java/com/example/agentchat/ui/modelconfig/ModelConfigDialog.kt`

- [ ] **Step 1: Add the state field.** Add `val lastSavedConfigId: String? = null` to `ModelConfigUiState`.

- [ ] **Step 2: Set and clear the signal at the source.** In `save`, after `repository.save(config, apiKeySnapshot, makeDefault)` succeeds, update state with `lastSavedConfigId = config.id`. In `resetForm`, `edit`, and `resetState`, set `lastSavedConfigId = null`. Keep the existing `isSaving`, validation, API-key masking, and error behavior unchanged.

- [ ] **Step 3: Add a dialog wrapper.** Create `ModelConfigDialog.kt` with this public composable contract:

```kotlin
@Composable
fun ModelConfigDialog(
    viewModel: ModelConfigViewModel,
    providerRegistry: ProviderRegistry,
    onUse: (ModelConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
        ) {
            ModelConfigScreen(
                viewModel = viewModel,
                providerRegistry = providerRegistry,
                onUse = onUse,
                onBack = onDismiss,
                inDialog = true,
            )
        }
    }
}
```

Use the existing `ModelConfig`, `ProviderRegistry`, `ModelConfigScreen`, and Compose Material imports. The dialog must not receive or log the API key.

- [ ] **Step 4: Add the `inDialog` parameter without breaking current callers.** Extend `ModelConfigScreen` with `inDialog: Boolean = false`; when true, use a bounded scrollable content area and label the primary action `保存并使用`, while the existing default remains compatible with current screen tests and callers. Keep the existing form fields and ViewModel calls.

- [ ] **Step 5: Run the focused ViewModel test.**

```bash
./gradlew :app:testDebugUnitTest --tests com.example.agentchat.ui.modelconfig.ModelConfigViewModelTest.successfulSavePublishesSavedConfigId
```

Expected: PASS.

## Task 3: Implement chat-page empty state and model switcher

**Files:**
- Modify: `app/src/main/java/com/example/agentchat/ui/chat/ChatScreen.kt`

- [ ] **Step 1: Split the header by model availability.** In `ChatScreenContent`, render only `Text("Agent Chat")` when `availableModels.isEmpty()`. When models exist, render the current model in the `model-selector` control, followed by the existing new-conversation and history actions.

- [ ] **Step 2: Add the empty-state button.** In the `state.messages.isEmpty()` branch, when `availableModels.isEmpty()`, render:

```kotlin
Button(
    onClick = onAddModelClick,
    modifier = Modifier.fillMaxWidth().testTag("add-model-empty-state"),
) {
    Text("＋ 添加第三方 LLM")
}
```

Keep the attachment, voice, keyboard-send, and history behavior unchanged.

- [ ] **Step 3: Add a stable test tag to the menu’s final action.** Set `Modifier.testTag("add-model-menu-item")` on the existing `TextButton` that displays `＋ 添加模型`; keep that item after all model rows and invoke `onAddModelClick()` only after dismissing the sheet.

- [ ] **Step 4: Run the focused instrumentation tests.**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.agentchat.ui.chat.ChatScreenTest
```

Expected: the new CTA and menu tests pass, and existing chat tests remain green.

## Task 4: Connect both entry points to the dialog in MainActivity

**Files:**
- Modify: `app/src/main/java/com/example/agentchat/MainActivity.kt`

- [ ] **Step 1: Add dialog state.** In `AgentChatContent`, add `var showModelConfig by remember { mutableStateOf(false) }` and a local callback:

```kotlin
val openModelConfig: () -> Unit = {
    container.modelConfigViewModel.resetForm()
    showModelConfig = true
}
```

- [ ] **Step 2: Route the chat callbacks.** Pass `onModelClick = openModelConfig` and `onAddModelClick = openModelConfig` to `ChatScreen`. Keep existing model-selection, history, attachment, and voice callbacks intact.

- [ ] **Step 3: Observe successful save and select the new model.** Add a `LaunchedEffect(configState.lastSavedConfigId, configState.configs)` that finds the saved config, calls `container.chatViewModel.setModel(config, providerRegistry.providerFor(config))`, and sets `showModelConfig = false`. If the ID is null or the config is not yet in the list, do nothing.

- [ ] **Step 4: Render the dialog overlay.** After the page content, when `showModelConfig` is true, render `ModelConfigDialog` with `onUse` selecting the config and closing the dialog, and `onDismiss = { showModelConfig = false }`. Use the existing provider registry and model ViewModel instances.

- [ ] **Step 5: Remove only the now-unused model-page routing.** Keep `Page.HISTORY`; remove the `Page.MODELS` branch and its imports only after the dialog callbacks compile. Do not remove `ModelConfigScreen`, because the dialog reuses it and its existing tests cover the form.

## Task 5: Full verification and manual run

**Files:**
- No additional source files.

- [ ] **Step 1: Run unit tests.**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` with no test failures.

- [ ] **Step 2: Run connected instrumentation tests.**

```bash
./gradlew :app:connectedDebugAndroidTest
```

Expected: all Compose/UI, Room, voice, attachment, history, and model-config instrumentation tests pass on `emulator-5554`.

- [ ] **Step 3: Build the debug APK.**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and no `:app:kspDebugKotlin` failure.

- [ ] **Step 4: Launch the app and manually verify the three flows.**

1. Clear local model data only through the app’s existing history/data-clear flow if needed; do not delete files from the repository.
2. Launch `app` on `Medium Phone (emulator-5554)`.
3. Verify no-model state shows `Agent Chat` and the large `＋ 添加第三方 LLM` button.
4. Tap it, verify the configuration dialog fields and validation, save a test configuration, and verify the dialog closes with that model selected.
5. Tap the model name, choose another configured model, and verify the menu closes.
6. Reopen the menu and tap `＋ 添加模型`; verify the same configuration dialog opens with a blank form.
7. Verify keyboard Enter/send, attachment, voice overlay, history, and stop-generation behavior remain available.

- [ ] **Step 5: Inspect the final diff before any commit.**

```bash
git diff --check
git diff --stat -- app/src/main app/src/androidTest app/src/test
git status --short --branch
```

Only the planned source/test files and the design/plan documents may be committed; do not stage tracked `app/build` outputs or machine-local files.
