package org.trybunal.examples;

import java.time.Duration;

/**
 * One row of the top-level summary table — totals for a single model's
 * phase-1 run.
 *
 * @param model     model name (e.g. {@code "llama3.1:8b"})
 * @param passed    cases that passed (deterministic + self-judged rubric)
 * @param total     total cases in the suite
 * @param duration  wall-clock duration of phase 1 for this model
 * @param note      free-text annotation (e.g. error string), or null
 */
record RunSummary(String model, int passed, int total, Duration duration, String note) {}
