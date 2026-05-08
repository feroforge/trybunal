/**
 * LLM-as-judge evaluator for Trybunal.
 *
 * <p>Uses a configured {@link org.trybunal.api.spi.ModelProvider} to grade
 * model output against a free-text rubric. Depends only on
 * {@code :trybunal-api}.</p>
 *
 * <p><b>Construction.</b> The judge needs a provider injected at construction
 * time. Because {@link java.util.ServiceLoader} requires a no-arg constructor,
 * the {@code @AutoRegistered} variant resolves a provider from a separate
 * {@link java.util.ServiceLoader} call when first invoked. See Task 07.</p>
 */
package org.trybunal.evaluator.llmjudge;
