# CLAUDE.md

> **Read [AGENTS.md](AGENTS.md) first.** It's the canonical contract for any
> agent working in this repo. This file only adds Claude-Code-specific notes
> that don't belong in the open AGENTS.md spec.

---

## When the user says "work on task N"

1. Open `tasks/phase-N/README.md`, `tasks/phase-N/NN-<name>.md`, and
   `tasks/phase-N/NN-<name>-acceptance.md`. Read all three before writing code.
2. Use `TodoWrite` to track the spec's "Files to add/edit" list as your todos.
   One in-progress at a time. Mark each completed only after the file actually
   builds.
3. Stay inside the spec's allowlist. No drive-by edits.
4. Before claiming the task is done, run `./gradlew build` and walk every
   acceptance checkbox. Your final message must list every file touched and
   the acceptance items you verified — see AGENTS.md §2.3.

## Tooling preferences

- **Always use `./gradlew`**, never a system `gradle`. The wrapper is pinned
  to Gradle 8.14 and the daemon to JDK 21 — both intentional.
- **Bash tool** for builds, file ops, and git inspection. Do not run `git
  commit` — the user owns commits.
- **Edit tool** for surgical changes; **Write tool** only for new files.
- Prefer `Read` over `cat` for files you're going to edit.
- For multi-step searches across the codebase, use the `Explore` agent rather
  than running grep/find sequentially yourself.

## Don'ts (Claude-specific)

- Don't use `mcp__computer-use__*` or browser tools for this project unless
  the user explicitly asks. Everything here is JVM + CLI.
- Don't auto-spawn subagents to "work on task N" — the user expects you to
  do it directly so they can watch the diff land.
- Don't suggest scheduling/cron — there's nothing recurring here.
- Don't add `@SuppressWarnings` to silence build warnings. Surface them.

## Repository facts that don't change

- JDK 21 toolchain, Gradle 8.14 wrapper, daemon pinned via
  `gradle/gradle-daemon-jvm.properties`.
- `trybunal-api` has zero deps beyond `slf4j-api`. Never add to it without
  asking.
- Modules talk to each other via `ServiceLoader` only. Direct module imports
  between `core` ↔ providers ↔ evaluators are forbidden.
- `tasks/` is gitignored — it's planning scratch, not shipped code.

## Where to find things

| Looking for | Path |
|---|---|
| Domain records, SPIs | `trybunal-api/src/main/java/org/trybunal/api/` |
| Orchestrator, harness | `trybunal-core/src/main/java/org/trybunal/core/` |
| Ollama provider | `trybunal-provider-ollama/src/main/java/org/trybunal/provider/ollama/` |
| Smoke runner | `examples/hello/src/main/java/org/trybunal/examples/HelloChat.java` |
| Phase plans | `tasks/phase-N/` (gitignored locally; ask if missing) |
| Build config | `settings.gradle.kts`, `build.gradle.kts`, per-module `build.gradle.kts` |

Everything else — architectural rules, definition of done, what NOT to do —
is in [AGENTS.md](AGENTS.md). Don't duplicate it here.
