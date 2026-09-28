# Local Skill System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement importable local `SKILL.md` files, deterministic task matching, Skill management UI, and AgentRuntime integration while preserving a disabled cloud adapter boundary.

**Architecture:** Skills are parsed into a validated domain model, persisted in Room, and matched locally by trigger/name/description scores. `ChatViewModel` passes the selected Skill to `AgentRuntime`, which injects its untrusted instructions and only allows declared tools already present in `AgentToolRegistry`. A `CloudSkillClient` interface is added without a production network implementation.

**Tech Stack:** Kotlin, Room, Jetpack Compose, Android Storage Access Framework, coroutines, JUnit, Compose Android tests.

---

### Task 1: Skill domain model, parser, validator, and matcher

**Files:**
- Create: `app/src/main/java/com/example/agentchat/domain/skill/Skill.kt`
- Create: `app/src/main/java/com/example/agentchat/domain/skill/SkillParser.kt`
- Create: `app/src/main/java/com/example/agentchat/domain/skill/SkillValidator.kt`
- Create: `app/src/main/java/com/example/agentchat/domain/skill/SkillMatcher.kt`
- Create: `app/src/test/java/com/example/agentchat/domain/skill/SkillParserTest.kt`
- Create: `app/src/test/java/com/example/agentchat/domain/skill/SkillMatcherTest.kt`

- [ ] **Step 1: Write parser tests first.** Cover valid front matter, defaults for version/triggers/tools, missing `id`/`name`/`description`/body, malformed front matter, and bounded body size.

```kotlin
@Test
fun parsesFrontMatterAndInstructionBody() {
    val result = SkillParser.parse("""
        ---
        id: travel-planner
        name: 旅行规划
        description: 规划旅行
        version: 1.0.0
        triggers: 旅行, 行程
        tools: web_search, calendar_context
        requires_confirmation: false
        ---
        先确认日期，再生成计划。
    """.trimIndent())

    assertEquals("travel-planner", result.getOrThrow().id)
    assertEquals(listOf("旅行", "行程"), result.getOrThrow().triggers)
    assertEquals("先确认日期，再生成计划。", result.getOrThrow().instructions)
}
```

- [ ] **Step 2: Run the focused parser tests and verify RED.**

Run:

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest --tests com.example.agentchat.domain.skill.SkillParserTest --console=plain
```

Expected: compilation failure because `SkillParser` and `Skill` do not exist.

- [ ] **Step 3: Implement the minimal parser.** Use a bounded `MAX_INSTRUCTION_CHARS`, require a closing `---`, parse only the documented keys, trim comma-separated values, reject blank required values, and return typed `SkillParseResult` errors instead of throwing raw parsing exceptions.

- [ ] **Step 4: Run parser tests and verify GREEN.** The focused command must finish with `BUILD SUCCESSFUL` and no failed tests.

- [ ] **Step 5: Write matcher tests first.** Verify exact trigger matches rank first, name/description matches score lower, disabled Skills are excluded, unknown declared tools make a Skill unavailable, and only three candidates are returned.

```kotlin
@Test
fun triggerMatchRanksAboveDescriptionMatch() {
    val result = SkillMatcher.match("帮我安排旅行行程", listOf(triggerSkill, descriptionSkill))
    assertEquals("travel-planner", result.first().skill.id)
}
```

- [ ] **Step 6: Implement `SkillMatcher` and `SkillValidator`.** Keep scoring deterministic and case-insensitive for Latin text; use exact substring matching for Chinese trigger terms; validate all declared tool names against a supplied `Set<String>`.

- [ ] **Step 7: Run parser and matcher tests.** Confirm all focused domain tests pass before touching Room or UI.

### Task 2: Room storage and local-data lifecycle

**Files:**
- Create: `app/src/main/java/com/example/agentchat/data/skill/SkillEntity.kt`
- Create: `app/src/main/java/com/example/agentchat/data/skill/SkillDao.kt`
- Create: `app/src/main/java/com/example/agentchat/data/skill/SkillRepository.kt`
- Modify: `app/src/main/java/com/example/agentchat/data/db/AgentDatabase.kt`
- Modify: `app/src/main/java/com/example/agentchat/AppContainer.kt`
- Test: `app/src/androidTest/java/com/example/agentchat/data/skill/SkillRepositoryTest.kt`

- [ ] **Step 1: Add the Room entity and DAO test fixture.** The entity must store ID, metadata, instruction body, enabled state, confirmation flag, source, and timestamps. DAO queries must observe enabled/all Skills ordered by updated time.

- [ ] **Step 2: Run the repository test before implementation.** Verify it fails because the table, DAO, and repository are absent.

- [ ] **Step 3: Add database version 5 and migration `MIGRATION_4_5`.** Create the `skills` table with a primary-key ID and non-null text/boolean fields. Register the entity and migration in `AgentDatabase` and `AppContainer` without changing previous migrations.

- [ ] **Step 4: Implement `SkillRepository`.** Expose `observeSkills`, `getSkill`, `save`, `setEnabled`, `delete`, and `deleteAll`. Map entities to domain objects and replace an existing ID transactionally.

- [ ] **Step 5: Extend local data clearing.** Call `skillRepository.deleteAll()` from `AppContainer.clearAllLocalData()` after the existing knowledge cleanup so Skills are removed together with local data.

- [ ] **Step 6: Run the Android repository test.** Verify import/save, observe, enable/disable, replace by ID, delete, and clear-all behavior.

### Task 3: Skill import and management UI

**Files:**
- Create: `app/src/main/java/com/example/agentchat/ui/skill/SkillUiState.kt`
- Create: `app/src/main/java/com/example/agentchat/ui/skill/SkillViewModel.kt`
- Create: `app/src/main/java/com/example/agentchat/ui/skill/SkillScreen.kt`
- Modify: `app/src/main/java/com/example/agentchat/MainActivity.kt`
- Test: `app/src/androidTest/java/com/example/agentchat/ui/skill/SkillScreenTest.kt`

- [ ] **Step 1: Write the Compose test for the empty state.** Assert the `Skills` title and import action are visible and the import callback fires.

```kotlin
compose.onNodeWithText("还没有 Skill").assertIsDisplayed()
compose.onNodeWithText("导入 Skill").performClick()
assertTrue(imported)
```

- [ ] **Step 2: Implement `SkillViewModel`.** Observe repository state, import a URI on `Dispatchers.IO`, read at most `MAX_SKILL_CHARS`, call `SkillParser`, save the parsed Skill, preserve cancellation, and expose a concise error without hiding existing Skills.

- [ ] **Step 3: Implement `SkillScreen`.** Follow the existing iOS-style knowledge screen: centered title bar, import card, rounded Skill cards, enabled switch, delete button, version/description text, and import error state. Do not add a second chat send control.

- [ ] **Step 4: Wire the screen into `MainActivity` and `AppContainer`.** Add a `Page.SKILLS`, a launcher for Markdown/text files, and a `Skills` navigation entry next to Knowledge Base. Return to chat without recreating the application-scoped repositories.

- [ ] **Step 5: Run focused Compose tests.** Verify empty state, imported Skill card, enable/disable, delete, long description layout, and import error display.

### Task 4: Manual and automatic Skill selection in AgentRuntime

**Files:**
- Create: `app/src/main/java/com/example/agentchat/domain/skill/SkillExecutionContext.kt`
- Modify: `app/src/main/java/com/example/agentchat/domain/agent/AgentRuntime.kt`
- Modify: `app/src/main/java/com/example/agentchat/ui/chat/ChatUiState.kt`
- Modify: `app/src/main/java/com/example/agentchat/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/example/agentchat/AppContainer.kt`
- Create: `app/src/test/java/com/example/agentchat/domain/skill/SkillRuntimeTest.kt`
- Modify: `app/src/test/java/com/example/agentchat/ui/chat/ChatViewModelTest.kt`

- [ ] **Step 1: Write the runtime test first.** Verify a selected Skill is included in the system prompt, its name/version are visible, unknown tools are not invoked, and a malformed Skill falls back to a normal request.

```kotlin
val context = runtime.enrich(
    query = "规划旅行",
    conversationId = "conversation",
    skill = travelSkill.toExecutionContext(),
)
assertTrue(context.prompt.contains("旅行规划"))
assertTrue(context.prompt.contains("先确认日期"))
```

- [ ] **Step 2: Extend `AgentRuntime.enrich` with an optional Skill context.** Add a delimited untrusted Skill section before retrieved references. Validate declared tool names against the registry and keep existing web/knowledge/history/calendar enrichment unchanged.

`SkillExecutionContext` contains `id`, `name`, `version`, `instructions`,
`validatedToolNames`, and `requiresConfirmation`. `Skill.toExecutionContext()`
is the only conversion used by the UI/runtime boundary.

- [ ] **Step 3: Add ChatViewModel selection state and intents.** Support `SkillSelected(id?)` for one-request manual selection, clear it after request completion, and ask the matcher for automatic candidates when no manual Skill is selected. Skill failures must not block the model provider.

- [ ] **Step 4: Add a compact Skill selector to `ChatScreen`.** Show `自动匹配` by default, list enabled candidates, and show the selected Skill name. Manual selection must update state without changing existing composer behavior.

- [ ] **Step 5: Inject SkillRepository/SkillMatcher into AppContainer.** Ensure the production graph uses the same application-scoped runtime and existing tool registry.

- [ ] **Step 6: Run runtime and ChatViewModel tests.** Confirm system context isolation, manual precedence, automatic fallback, and existing chat tests.

### Task 5: Cloud adapter boundary

**Files:**
- Create: `app/src/main/java/com/example/agentchat/domain/skill/CloudSkillClient.kt`
- Create: `app/src/main/java/com/example/agentchat/data/skill/DisabledCloudSkillClient.kt`
- Test: `app/src/test/java/com/example/agentchat/domain/skill/CloudSkillClientTest.kt`

- [ ] **Step 1: Define the interface without network behavior.** Use `suspend fun listSkills(): List<Skill>`, `suspend fun match(query: String, conversationId: String): List<Skill>`, and `fun run(skillId: String, query: String, conversationId: String): Flow<CloudSkillEvent>`. Define `CloudSkillEvent` in the same file with `Started`, `Context(AgentContext)`, `Failed(code, message)`, and `Completed` variants.

- [ ] **Step 2: Implement the disabled client.** Return an explicit `cloud_disabled` failure and never make a network request. Inject it in `AppContainer` so future server work does not require changing ChatViewModel signatures again.

- [ ] **Step 3: Test the disabled boundary.** Assert the failure code and that no provider/tool call is made.

### Task 6: Full verification and release artifact

**Files:**
- Modify: `README.md` with local Skill import format and safety limitations.
- No generated APK or `app/build` files are committed.

- [ ] **Step 1: Run all JVM unit tests.**

```bash
./gradlew --no-daemon --max-workers=1 testDebugUnitTest --console=plain
```

- [ ] **Step 2: Run available Android tests.** Run focused Skill tests first, then the full connected suite. If the existing Espresso/Android 17 `InputManager.getInstance` compatibility error remains, report it separately from business assertions.

- [ ] **Step 3: Run Lint and build.**

```bash
./gradlew --no-daemon --max-workers=1 lintDebug assembleDebug --console=plain
```

- [ ] **Step 4: Inspect the artifact.** Record APK size and SHA-256 from `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5: Review the diff.** Confirm only Skill implementation, documentation, tests, migrations, and necessary version metadata are staged; exclude `app/build`, APK copies, caches, `.hprof`, and local properties.
