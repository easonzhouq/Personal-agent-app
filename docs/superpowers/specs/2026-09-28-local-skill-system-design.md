# Local Skill System Design

## Goal

Add an extensible local Skill system to the Android Agent app. A Skill is a
user-importable `SKILL.md` instruction package that can be selected manually or
matched to a task, then applied by the existing `AgentRuntime` and
`AgentToolRegistry`. The design also reserves a stable cloud adapter boundary
for a later Agent Gateway without requiring a cloud service in this phase.

This phase solves task-specific behavior through trusted local tools and model
instructions. It does not execute arbitrary Kotlin, JavaScript, shell commands,
or code embedded in a Skill file.

## Scope and non-goals

In scope:

- Import Markdown Skill files through Android Storage Access Framework.
- Store Skill metadata and instructions locally in Room.
- List, inspect, enable, disable, and delete Skills from a dedicated screen.
- Match enabled Skills to a user query using deterministic local scoring.
- Allow the user to manually choose a Skill for the next request.
- Inject the selected Skill instructions into the existing Agent Runtime context.
- Validate declared tool names and require confirmation for sensitive actions.
- Define a cloud Skill client interface and configuration placeholder; no cloud
  server deployment or network Skill execution is included yet.

Out of scope:

- Running arbitrary code from a Skill.
- Installing third-party Android packages or plugins.
- Automatic downloading of Skills from untrusted URLs.
- Cloud account, billing, multi-device synchronization, or server deployment.
- Replacing the current model provider configuration.

## Skill format

The first version accepts UTF-8 Markdown files named `SKILL.md` or any Markdown
file selected by the user. The file uses a small front matter block followed by
the instruction body:

```markdown
---
id: travel-planner
name: 旅行规划
description: 根据目的地、天气和日历生成旅行计划
version: 1.0.0
triggers: 旅行,旅游,行程,酒店,景点
tools: web_search,knowledge_search,calendar_context
requires_confirmation: false
---

你是旅行规划助手。先确认目的地和日期，再结合可靠来源生成计划。
```

Parsing rules:

- `id`, `name`, and `description` are required.
- `version` defaults to `1.0.0` when omitted.
- `triggers` and `tools` are comma-separated lists; both default to empty.
- The body is required and is limited to a bounded size.
- Duplicate IDs replace the existing Skill only after user confirmation in the
  import flow.
- Unknown front matter keys are ignored for forward compatibility.
- The body is treated as untrusted instructions and is never allowed to change
  system safety rules or bypass confirmation requirements.

## Architecture

```text
SkillScreen / Chat manual selection
              |
       SkillRepository (Room)
              |
       SkillMatcher ---- SkillValidator
              |
       SkillExecutionContext
              |
         AgentRuntime
              |
       AgentToolRegistry
              |
  search / knowledge / history / calendar
```

### Domain model

Add a `Skill` domain model with:

- stable `id`;
- display `name`, `description`, and `version`;
- trigger terms;
- declared tool names;
- instruction body;
- enabled state;
- `requiresConfirmation` flag;
- created and updated timestamps.

Add a `SkillSource` value identifying imported local Skills versus future cloud
manifests. The source is informational in this phase and does not grant extra
permissions.

### Persistence

Add a Room `skills` table and a migration from the current database version.
The repository exposes:

- `observeSkills()`;
- `getSkill(id)`;
- `saveSkill(skill)`;
- `setEnabled(id, enabled)`;
- `deleteSkill(id)`;
- `deleteAllSkills()` for local-data clearing.

Skill instructions are stored as text. API keys and other secrets are never
stored in Skill records.

### Parsing and validation

`SkillParser` converts Markdown into a domain model and returns typed validation
errors for missing fields, invalid IDs, oversized bodies, malformed front
matter, and duplicate tool declarations. `SkillValidator` checks that every
declared tool exists in the local tool registry. Unknown tools do not execute;
the matching result reports the Skill as unavailable until corrected.

The parser is deterministic and independent of Android UI, so it can be tested
with JVM unit tests.

### Matching

`SkillMatcher.match(query, enabledSkills)` calculates a bounded score from:

1. exact trigger-term matches;
2. name matches;
3. description matches.

Results below a minimum score are ignored. The matcher returns at most three
candidate Skills, ordered by score and then by explicit user preference. A
manual selection takes precedence over automatic matching for one request and
is cleared after the request completes.

The model is not used as the matcher in this phase. This keeps behavior
predictable, avoids an extra network call, and prevents Skill instructions from
being selected solely by untrusted generated text.

### Agent Runtime integration

Extend `AgentRuntime.enrich` with an optional `SkillExecutionContext`:

- include the selected Skill body in a clearly delimited system context;
- include the Skill name and version for response traceability;
- invoke only declared, validated tools through `AgentToolRegistry`;
- preserve the current web-search, knowledge, history, and calendar behavior;
- continue treating retrieved data and Skill text as untrusted reference data.

The runtime must remain best-effort for optional tools. Cancellation must pass
through. A malformed or unavailable Skill must not prevent a normal chat
request; the UI should show a non-blocking diagnostic and continue without that
Skill.

Sensitive capabilities, including calendar writes and future device actions,
remain behind the existing confirmation boundary. `requiresConfirmation` can
make the UI request confirmation earlier, but it cannot remove confirmation.

### Cloud adapter boundary

Add an interface such as `CloudSkillClient` without enabling it by default:

- `listSkills()`;
- `match(query, conversationId)`;
- `run(skillId, query, conversationId)` as a streaming result.

The Android app will later configure an HTTPS gateway URL and authenticated
session. In this phase the interface has no production implementation and local
Skills remain the only executable Skills. The future cloud response must use
the same `AgentContext`/tool result boundary so the UI does not need a second
chat pipeline.

## UI

Add a `Skills` entry next to the existing knowledge-base entry. The Skills
screen contains:

- an empty state with an import action;
- a list of Skill cards showing name, description, version, and enabled state;
- enable/disable control;
- delete action;
- import progress and typed error messages.

The chat screen adds a compact Skill selector. It shows `自动匹配` by default,
allows one manual Skill selection for the next message, and displays the
selected Skill name in the request status area. It does not add another send
button or change the existing composer interaction.

## Safety and privacy

- Only user-selected local files are imported.
- Skill files cannot access the filesystem, network, calendar, microphone, or
  other device APIs directly.
- Tool access is allowlisted by `AgentToolRegistry`.
- Calendar writes and future device actions require user confirmation.
- Skill body and retrieved content are isolated from system rules in the model
  prompt.
- Local Skill content remains on-device in this phase.
- Diagnostics must not include API keys, raw attachment data, or full private
  documents.

## Error handling

Typed errors are surfaced for:

- invalid or oversized Skill files;
- missing required metadata;
- duplicate IDs;
- unknown tools;
- disabled or unavailable Skills;
- cancelled imports or executions.

Automatic matching failures are non-blocking. If a Skill fails validation or
execution, the agent sends the normal request without it and exposes a concise
message that the user can inspect from the chat state.

## Testing strategy

JVM unit tests cover:

- front matter parsing and defaults;
- malformed and oversized documents;
- duplicate IDs and unknown tools;
- deterministic matching and score ordering;
- enabled/disabled filtering;
- runtime prompt isolation and selected Skill injection;
- cancellation and best-effort failure behavior.

Android tests cover:

- Skills empty state and import action;
- list, enable/disable, and delete interactions;
- manual Skill selection in chat;
- automatic matching indicator and fallback state.

Existing provider, RAG, calendar, and chat tests must continue to pass. APK
verification remains `testDebugUnitTest`, `lintDebug`, and `assembleDebug`.

## Rollout

1. Add parser, domain model, matcher, and JVM tests.
2. Add Room schema/migration and repository tests.
3. Add Skill screen and import flow.
4. Add manual selection and automatic matching to `AgentRuntime`.
5. Add cloud adapter interfaces only, disabled by default.
6. Run full verification and produce a debug APK.
