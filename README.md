# Trybunal

A modular ecosystem for testing, evaluating, and hosting AI agents.

> **Phase 1 — The Middleman.** This release ships the structural skeleton:
> the API contracts, the orchestrator, and a working Ollama provider.

## Modules

| Module | Role |
|---|---|
| `trybunal-api` | Pure domain records and SPIs. Only depends on slf4j-api. |
| `trybunal-core` | The orchestrator and reference harness. Never references a provider directly. |
| `trybunal-provider-ollama` | Local LLM provider via the Ollama HTTP API. |
| `examples:hello` | Minimal CLI smoke runner: User → Orchestrator → Ollama. |

## Architecture

Hexagonal. The domain (`trybunal-api`) defines `ModelProvider` and
`ModelHarness`. Implementations live in separate modules and are discovered
via `ServiceLoader`. Cross-cutting concerns (latency, logging, future tool
dispatch) live exclusively in the harness layer.

## Quick start

```bash
./gradlew build
OLLAMA_HOST=http://localhost:11434 ./gradlew :examples:hello:run
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the Trybunal Pattern when adding new
providers, tools, or evaluators.

## Requirements

- JDK 21+
- (Optional, for the Ollama provider) a local Ollama daemon
