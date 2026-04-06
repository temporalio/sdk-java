package io.temporal.springai.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool class as deterministic, meaning it is safe to execute directly in a Temporal
 * workflow without wrapping in an activity or side effect.
 *
 * <p>Deterministic tools must:
 *
 * <ul>
 *   <li>Always produce the same output for the same input
 *   <li>Have no side effects (no I/O, no random numbers, no system time)
 *   <li>Not call any non-deterministic APIs
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @DeterministicTool
 * public class MathTools {
 *     @Tool(description = "Add two numbers")
 *     public int add(int a, int b) {
 *         return a + b;
 *     }
 *
 *     @Tool(description = "Multiply two numbers")
 *     public int multiply(int a, int b) {
 *         return a * b;
 *     }
 * }
 *
 * // In workflow:
 * this.chatClient = TemporalChatClient.builder(activityChatModel)
 *         .defaultTools(new MathTools())  // Safe to use directly
 *         .build();
 * }</pre>
 *
 * <p><b>Warning:</b> Using this annotation on a class that performs non-deterministic operations
 * will break workflow replay. Only use this for truly deterministic computations.
 *
 * @see org.springframework.ai.tool.annotation.Tool
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DeterministicTool {}
