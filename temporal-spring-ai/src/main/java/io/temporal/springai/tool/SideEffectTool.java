package io.temporal.springai.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool class as a side-effect tool, meaning its methods will be wrapped in {@code
 * Workflow.sideEffect()} for safe execution in a Temporal workflow.
 *
 * <p>Side-effect tools are useful for operations that:
 *
 * <ul>
 *   <li>Are non-deterministic (e.g., reading current time, generating UUIDs)
 *   <li>Are cheap and don't need the full durability of an activity
 *   <li>Don't have external side effects that need to be retried on failure
 * </ul>
 *
 * <p>The result of a side-effect tool is recorded in the workflow history, so on replay the same
 * result is returned without re-executing the tool.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @SideEffectTool
 * public class TimestampTools {
 *     @Tool(description = "Get the current timestamp")
 *     public long currentTimeMillis() {
 *         return System.currentTimeMillis();  // Non-deterministic, but recorded
 *     }
 *
 *     @Tool(description = "Generate a random UUID")
 *     public String randomUuid() {
 *         return UUID.randomUUID().toString();
 *     }
 * }
 *
 * // In workflow:
 * this.chatClient = TemporalChatClient.builder(activityChatModel)
 *         .defaultTools(new TimestampTools())  // Wrapped in sideEffect()
 *         .build();
 * }</pre>
 *
 * <p><b>When to use which annotation:</b>
 *
 * <ul>
 *   <li>{@link DeterministicTool} - Pure functions with no side effects (math, string manipulation)
 *   <li>{@code @SideEffectTool} - Non-deterministic but cheap operations (timestamps, random
 *       values)
 *   <li>Activity stub - Operations with external side effects or that need retry/durability
 * </ul>
 *
 * @see DeterministicTool
 * @see io.temporal.workflow.Workflow#sideEffect(Class, io.temporal.workflow.Functions.Func)
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SideEffectTool {}
