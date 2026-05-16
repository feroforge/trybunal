/**
 * Default {@link org.trybunal.api.spi.ConversationCompactor} that
 * rewrites oversized {@link org.trybunal.api.model.Message.Tool}
 * results to short stubs while keeping the system prompt, the first
 * user turn, and the most recent assistant/tool exchange verbatim.
 *
 * <p>Registered for {@link java.util.ServiceLoader} discovery under
 * {@code META-INF/services/org.trybunal.api.spi.ConversationCompactor}.</p>
 */
package org.trybunal.compactor.toolresults;
