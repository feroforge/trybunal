package org.trybunal.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * A tiny in-process todo list the agent can use to externalise its plan.
 *
 * <p>Small local models (gemma4:26b in particular) routinely lose track of
 * which step of a multi-step task they are on, looping on one step or
 * skipping past important ones. Giving the model an explicit
 * read-modify-write list — and requiring it to plan with the list as its
 * first action — restores progress in the ReAct loop.</p>
 *
 * <p>Two actions, one tool. Either {@code set} (replace the list) or
 * {@code complete} (mark the first item whose text starts with the given
 * prefix as done). Both actions return the full current state, so the model
 * sees exactly which items remain after every call.</p>
 *
 * <p>This is example code, not a Phase 4 tool — there is no
 * {@code META-INF/services} registration. ResearchAgent wires it in
 * explicitly via {@code Orchestrator.of(...)}.</p>
 */
public final class TodoTool implements Tool {

    private static final String NAME = "todo";

    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Plan and track a multi-step task. Call once with `set` to register "
                    + "your plan; then call with `complete` after each step you finish. "
                    + "Every call returns the full current list — checked items are [x], "
                    + "unchecked are [ ]. ALWAYS make this your first tool call.",
            buildSchema());

    private final List<Item> items = new ArrayList<>();

    @Override public ToolSpec spec() { return SPEC; }

    @Override public synchronized ToolResult invoke(Map<String, Object> arguments) {
        if (arguments == null) arguments = Map.of();
        Object setArg = arguments.get("set");
        Object completeArg = arguments.get("complete");

        if (setArg != null) {
            // Refuse re-sets when a list already exists — small models tend to
            // re-plan on every iteration instead of progressing. The error
            // message points them at the only useful next move.
            if (!items.isEmpty()) {
                String next = nextUnchecked();
                String msg = "ERROR: a plan is already registered. Do not call "
                        + "todo(set=...) again. Current state:\n" + render() + "\n";
                if (next != null) {
                    msg += "Next action: do the step \"" + next + "\" using web_fetch "
                         + "or cite, then call todo(complete=\""
                         + next.substring(0, Math.min(next.length(), 20)) + "\").";
                } else {
                    msg += "All items are checked — write your final summary now, "
                         + "do NOT call any more tools.";
                }
                return ToolResult.error(msg);
            }
            if (setArg instanceof List<?> list) {
                for (Object o : list) {
                    if (o == null) continue;
                    String s = o.toString().trim();
                    if (!s.isEmpty()) items.add(new Item(s, false));
                }
            } else {
                // some models pass a single string with newlines instead of a list
                for (String s : setArg.toString().split("\\R")) {
                    String t = s.trim().replaceFirst("^[-*0-9.) ]+", "").trim();
                    if (!t.isEmpty()) items.add(new Item(t, false));
                }
            }
        }

        if (completeArg != null) {
            String target = completeArg.toString().trim().toLowerCase();
            boolean marked = false;
            for (Item it : items) {
                if (!it.done && it.text.toLowerCase().startsWith(target)) {
                    it.done = true;
                    marked = true;
                    break;
                }
            }
            if (!marked) {
                // accept partial substring match as a fallback
                for (Item it : items) {
                    if (!it.done && it.text.toLowerCase().contains(target)) {
                        it.done = true;
                        marked = true;
                        break;
                    }
                }
            }
            if (!marked) {
                return ToolResult.ok("no matching item to complete; current list:\n" + render());
            }
        }

        if (setArg == null && completeArg == null) {
            return ToolResult.ok(render());
        }
        return ToolResult.ok(render());
    }

    private String nextUnchecked() {
        for (Item it : items) {
            if (!it.done) return it.text;
        }
        return null;
    }

    private String render() {
        if (items.isEmpty()) return "(empty list)";
        int done = 0;
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            sb.append(it.done ? "[x] " : "[ ] ").append(it.text).append('\n');
            if (it.done) done++;
        }
        sb.append("-- ").append(done).append('/').append(items.size())
          .append(" complete");
        if (done == items.size()) sb.append(" — ALL DONE, write your final summary now");
        return sb.toString();
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> setProp = new LinkedHashMap<>();
        setProp.put("type", "array");
        setProp.put("items", Map.of("type", "string"));
        setProp.put("description",
                "Optional. Replace the list with these items, all unchecked. Use this "
                + "once at the start of the task to register your plan.");

        Map<String, Object> completeProp = new LinkedHashMap<>();
        completeProp.put("type", "string");
        completeProp.put("description",
                "Optional. Mark the first matching item as done. Pass the start "
                + "(prefix) of the item's text; matching is case-insensitive.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("set", setProp);
        properties.put("complete", completeProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // No required fields — calling with neither arg returns the current list.
        return schema;
    }

    private static final class Item {
        final String text;
        boolean done;
        Item(String text, boolean done) { this.text = text; this.done = done; }
    }
}
