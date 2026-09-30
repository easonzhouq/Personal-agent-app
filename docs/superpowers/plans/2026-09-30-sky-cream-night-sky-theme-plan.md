# Sky Cream and Night Sky Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current gray/iOS palette with the approved Sky Cream light theme and Night Sky dark theme without changing layout or behavior.

**Architecture:** Keep `AgentChatTheme` and Material 3 as the single theme boundary. Rename the existing color constants to semantic palette tokens, map them in `Theme.kt`, and add JVM assertions for the approved hex values. Existing screens continue consuming `MaterialTheme.colorScheme`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit, Gradle Android build.

---

### Task 1: Lock the approved color tokens with tests

**Files:**
- Create: `app/src/test/java/com/example/agentchat/ui/theme/ThemeColorTest.kt`

- [ ] **Step 1: Write the failing tests**

Assert that the palette exposes these exact values: light background `#F7FBFF`, light primary `#5A9BD6`, dark background `#101A28`, dark primary `#8CC4F4`, and error colors `#D95C62` / `#FF9A9F`.

- [ ] **Step 2: Run the focused test**

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest --tests '*ThemeColorTest'
```

Expected: compilation fails because the new semantic token names do not exist.

### Task 2: Implement the Sky Cream/Night Sky palette

**Files:**
- Modify: `app/src/main/java/com/example/agentchat/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/example/agentchat/ui/theme/Theme.kt`

- [ ] **Step 1: Define semantic color constants**

Add light tokens for the approved Sky Cream values and dark tokens for the approved Night Sky values. Keep the colors as `Color` constants in `Color.kt`; do not add screen-specific colors.

- [ ] **Step 2: Map both Material 3 schemes**

Set `primary`, `onPrimary`, `secondary`, `background`, `surface`, `surfaceVariant`, `onBackground`, `onSurface`, `onSurfaceVariant`, `outline`, and `error` in both `lightColorScheme` and `darkColorScheme` using the new constants.

- [ ] **Step 3: Run the focused test**

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest --tests '*ThemeColorTest'
```

Expected: all color assertions pass.

- [ ] **Step 4: Commit the theme change**

```bash
git add app/src/main/java/com/example/agentchat/ui/theme/Color.kt app/src/main/java/com/example/agentchat/ui/theme/Theme.kt app/src/test/java/com/example/agentchat/ui/theme/ThemeColorTest.kt
git commit -m "feat(android): add sky cream night sky theme"
```

### Task 3: Verify the app-wide visual change

**Files:**
- No additional source files; inspect existing Compose screens using `MaterialTheme.colorScheme`.

- [ ] **Step 1: Run all JVM tests**

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest
```

Expected: exit code 0.

- [ ] **Step 2: Compile, lint, and assemble**

```bash
./gradlew --no-daemon --max-workers=1 compileDebugKotlin compileDebugAndroidTestKotlin lintDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL` with no new lint errors.

- [ ] **Step 3: Check the final diff**

```bash
git diff --check
git status --short --branch
```

Confirm build outputs and temporary files are not staged.

