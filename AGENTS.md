# AGENTS.md — Working on Trybunal

This file is the contract between you (an agent) and this codebase. Read it
end-to-end before writing any code. Re-read it whenever the user asks you to
"work on task N" or to "implement task N."

> **CLAUDE.md** in this repo defers to this file. Anything Claude-Code-specific
> lives there; everything else is here.

---

## 1. What this project is

Trybunal is a modular Java 21 ecosystem for testing, evaluating, and hosting
AI agents. Architecture: hexagonal (ports & adapters), Gradle multi-module,
SPI-based extension via `ServiceLoader`. README.md has the high-level pitch.

The repo is built phase-by-phase. Phase 1 is committed. Subsequent phases are
broken into small task files under `tasks/phase-N/`.

---

## 2. The task workflow

When the user says **"work on task N"** or **"implement task N"**, this is what they mean:

### 2.1 Read order — do not skip

1. Read `tasks/phase-N/README.md` for context and architectural invariants.
2. Read `tasks/phase-N/NN-<name>.md` (the task spec) end-to-end.
3. Read `tasks/phase-N/NN-<name>-acceptance.md` (the acceptance checklist).
4. Read any files the spec references — APIs you'll touch, sibling tests, etc.

If you cannot find a referenced file, **stop and ask the user**. Do not invent
file paths or guess.

### 2.2 Implementation rules

- **Stay inside the spec's "Files to add" / "Files to edit" lists.** No
  drive-by refactors. No "while I'm here" cleanups in unrelated files.
- **Follow the type signatures and structure given in the spec verbatim.**
  If you think the spec is wrong, surface the disagreement to the user
  *before* deviating.
- **Do not add dependencies** unless the spec explicitly lists them.
- **Match the existing style.** Records over POJOs. Sealed interfaces over
  enums-with-data. Public methods never return `null` collections — use
  `List.of()` / `Map.of()`.
- **Javadoc every new public type.** Include a one-line *contract* paragraph
  that another agent or human can read in isolation. State preconditions,
  postconditions, and what `null` means for every parameter.
- **Tests are part of the task.** A task is not done until its tests are
  written and green.

### 2.3 Definition of done

A task is complete only when *all* of the following are true:

1. `./gradlew build` exits 0 with no new warnings (deprecation notes are OK if pre-existing).
2. The task's new tests appear in JUnit output and pass.
3. You have walked through every checkbox in `NN-<name>-acceptance.md` and can
   honestly tick each one.
4. You have not modified files outside the task's allowlist.

When you finish a task, your final message to the user should:
- Name the task.
- List every file you added or edited (relative paths).
- State which acceptance items you verified and how.
- Show the relevant `./gradlew` output (last ~10 lines).
- Flag anything in the spec you found unclear, contradictory, or worth
  improving for future tasks.

### 2.4 What NOT to do

- Don't start the next task on your own. Tasks are merged one at a time.
- Don't edit `tasks/phase-N/*.md` files unless the user explicitly asks.
- Don't delete or rewrite existing tests to make your code pass.
- Don't suppress warnings with `@SuppressWarnings` to make the build clean.
  Fix the underlying issue or surface it to the user.
- Don't add a `main` method to a library module to "test things." Use unit tests.
- Don't commit. The user owns commits.

---

## 3. Architectural invariants (the Trybunal Pattern)

These rules apply to every module in the repo and are not negotiable
without a discussion.

### 3.1 Module dependency rules

- **`trybunal-api` depends on nothing but `slf4j-api`.** It is the domain.
  Adding any other dependency is a discussion, not a PR.
- **Every other module depends on `trybunal-api`** and only on
  `trybunal-api`. Modules never depend on each other directly.
  - `trybunal-core` depends only on `:trybunal-api`.
  - Providers depend only on `:trybunal-api`.
  - Evaluators depend only on `:trybunal-api`.
- Wiring between modules happens at the application edge (via
  `ServiceLoader`) — never via direct module imports.

### 3.2 The Provider / Evaluator pattern

Every extension is an SPI implementation in its own Gradle module:

- Module name: `trybunal-<kind>-<slug>` (e.g. `trybunal-provider-openai`,
  `trybunal-evaluator-assertions`).
- Implements exactly one SPI from `org.trybunal.api.spi`.
- Registers via `META-INF/services/<fully-qualified-spi-name>`.
- Public no-arg constructor (ServiceLoader requirement).
- Implementations MUST be thread-safe — the orchestrator calls them from
  virtual threads.

### 3.3 Cross-cutting concerns

Timing, slf4j request-lifecycle logging, retries, MDC keys, and tool
dispatch live in the **harness/orchestrator layer only**.

- Providers may log transport-level errors (`log.warn("transport failed: ...")`).
- Providers may NOT measure latency, log request/response payloads at INFO,
  or implement retries.
- Evaluators may NOT log or measure timing — the orchestrator does that.

If you find yourself adding `MDC.put(...)` or `Instant.now()` inside a
provider or evaluator, stop. You're in the wrong layer.

### 3.4 Domain modeling

- **Records or sealed interfaces over records.** No setters. No abstract classes.
- **Validate in the compact constructor.** Throw `IllegalArgumentException` for
  bad input. Defensively copy collections.
- **One concept per file.** If an agent has to scroll, the file is too long.
- **Names are sentences.** `resolveFor`, `materialize`, `withOverride` —
  never `proc`, `doIt`, `handle`.

---

## 4. Build & toolchain

- **JDK 21** is the toolchain. The Gradle daemon is pinned to JDK 21 via
  `gradle/gradle-daemon-jvm.properties`.
- **Gradle 8.14** (via the wrapper). Always use `./gradlew`, never a system
  `gradle`.
- **`foojay-resolver-convention`** auto-provisions JDK 21 on machines that
  don't have it.

### Common commands

```bash
./gradlew build                                  # full build + tests
./gradlew :trybunal-api:test --info              # one module, verbose
./gradlew :examples:hello:run --console=plain    # smoke test (needs Ollama)
./gradlew --stop                                 # stop the daemon
```

### When the build is broken

1. `./gradlew --stop && rm -rf .gradle build */build */*/build`
2. `./gradlew build --no-daemon`
3. If still broken, paste the last 50 lines of output to the user.

---

## 5. Repository layout

```
trybunal/
├── README.md                        # project pitch
├── CONTRIBUTING.md                  # human-facing contributor guide
├── AGENTS.md                        # this file
├── CLAUDE.md                        # Claude Code memory; defers here
├── settings.gradle.kts              # module list
├── build.gradle.kts                 # shared subproject config
├── gradle/
│   ├── wrapper/                     # COMMITTED — needed for ./gradlew
│   └── gradle-daemon-jvm.properties # pins daemon to JDK 21
├── trybunal-api/                    # domain + SPIs (no logic)
├── trybunal-core/                   # orchestrator + reference harness
├── trybunal-provider-ollama/        # Ollama HTTP provider
├── examples/
│   └── hello/                       # User → Orchestrator → Ollama smoke runner
└── tasks/                           # IGNORED by git — planning docs
    └── phase-2/
        ├── README.md
        ├── 01-<name>.md             # task spec
        ├── 01-<name>-acceptance.md  # human checklist
        └── ...
```

`tasks/` is gitignored on purpose — it's planning scratch, not part of the
shipped artifact. Don't put runtime code there.

---

## 6. Phase status

- **Phase 1 — The Middleman** ✅ committed.
  Hexagonal skeleton, Ollama provider, hello example, JDK 21 / Gradle 8.14 toolchain.
- **Phase 2 — Evaluation** 📋 planned in `tasks/phase-2/`. 10 task pairs
  covering code-based evaluators, LLM-as-judge, orchestrator integration,
  and report rendering.
- **Phase 3+** — TBD.

---

## 7. When in doubt

Ask the user. Specifically:

- If the task spec contradicts the existing code, ask which is authoritative.
- If a dependency you'd "normally" add isn't listed, ask first.
- If you can't get the build green after one clean rebuild, surface the
  failure with the actual error — don't try three different fixes silently.
- If you finish a task and notice an obvious follow-up issue, mention it in
  your wrap-up message instead of fixing it inline.

The user is steering the architecture. Your job is to land the task they
asked for, exactly as specified, with the build green and the acceptance
checklist honestly walked through.
