# Contributing to Trybunal

Trybunal is built to be extended by humans **and** AI coding agents. Follow the
"Trybunal Pattern" so your module composes cleanly with the rest of the system.

## The Trybunal Pattern

A new module is one of three kinds: **Provider**, **Tool**, or **Evaluator**.
Each kind has a single SPI in `trybunal-api`. Implement the SPI, register it
via `META-INF/services`, and you are done — no edits to `trybunal-core` required.

### Module checklist (applies to all kinds)

1. **Name:** `trybunal-<kind>-<slug>` (e.g. `trybunal-provider-openai`).
2. **Depend only on `trybunal-api`.** Never on `trybunal-core` or another provider.
3. **Implement exactly one SPI.** Keep classes package-private except the SPI impl.
4. **Register via ServiceLoader.** Add `META-INF/services/org.trybunal.api.spi.<Spi>`
   listing your fully-qualified class name.
5. **Stay stateless or document concurrency.** Providers MUST be thread-safe;
   the orchestrator invokes them from virtual threads.
6. **No cross-cutting code.** Timing, retries, and slf4j request-lifecycle
   logging belong in the harness layer (`DefaultModelHarness`). Providers may
   only log transport-level errors.
7. **Javadoc every public type.** Include a one-line *contract* paragraph that
   an LLM can read in isolation. State preconditions, postconditions, and what
   "null" means for every parameter.
8. **Tests:** ship a contract test that runs the SPI's standard test suite
   against your implementation. Live integration tests must be gated on an
   environment variable (see `OllamaProviderIntegrationTest`) so CI without
   credentials still passes.

### LLM-readability rules

- Prefer `record` over POJOs; prefer sealed interfaces over enums-with-data.
- Names are sentences: `resolveFor`, `materialize`, `withOverride` — never `proc`.
- Public methods never return `null` collections; use `List.of()` / `Map.of()`.
- One concept per file. If an agent has to scroll, the file is too long.

### Adding a Provider — concrete steps

1. Create `trybunal-provider-<slug>` module; depend on `:trybunal-api`.
2. Implement `org.trybunal.api.spi.ModelProvider`. Translate `List<Message>` ⇄
   your wire format using a `switch` on the sealed `Message` hierarchy.
3. Populate `InvocationMetadata` — at minimum `modelId`, `startedAt`, and
   `latency` (the harness will overwrite latency with measured wall-clock,
   but providers should still set non-null).
4. Register in `META-INF/services/org.trybunal.api.spi.ModelProvider`.
5. Add a contract test plus a live integration test gated on an env var.

### Pull request expectations

- One module per PR. Cross-module changes need a design note in the description.
- Public API changes require a CHANGELOG entry and a `@since` Javadoc tag.

## Build

```bash
./gradlew build         # compile + unit tests
./gradlew :examples:hello:run    # User → Orchestrator → Ollama smoke test
```

The smoke runner needs a local Ollama daemon and `llama3.1:8b` pulled. Override
the model with `-Dtrybunal.model=...` and the host with `OLLAMA_HOST=...`.
