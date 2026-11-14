package io.temporal.nexus;

import io.nexusrpc.handler.OperationCancelDetails;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.common.Experimental;
import java.lang.annotation.*;

/**
 * Marks a method on a {@link ServiceImpl}-annotated class as a Temporal-backed Nexus operation. The
 * method body <i>is</i> the start handler — the framework wraps it in a {@link
 * TemporalOperationHandler} at registration time, with default cancel behavior matching {@link
 * TemporalOperationHandler#cancel(OperationContext, OperationCancelDetails)}.
 *
 * <p>The declaring {@link ServiceImpl}-annotated class must be {@code public} so the annotated
 * method can be accessed at registration time. The method must:
 *
 * <ul>
 *   <li>be {@code public},
 *   <li>accept exactly two parameters: {@link TemporalNexusClient} and the operation input type,
 *   <li>return the operation output type directly for synchronous completion, or {@link
 *       TemporalOperationResult} for explicit synchronous or asynchronous completion.
 * </ul>
 *
 * <p>Workflow-run example:
 *
 * <pre>{@code
 * @ServiceImpl(service = TransferService.class)
 * public class TransferServiceImpl {
 *   @TemporalOperation
 *   public TemporalOperationResult<TransferResult> transfer(
 *       TemporalNexusClient client, TransferInput input) {
 *     return client.startWorkflow(
 *         TransferWorkflow.class,
 *         TransferWorkflow::transfer,
 *         input,
 *         WorkflowOptions.newBuilder()
 *             .setWorkflowId("transfer-" + input.getTransferId())
 *             .build());
 *   }
 * }
 * }</pre>
 *
 * <p>A synchronous operation can return its output directly. The SDK wraps the returned value in
 * {@link TemporalOperationResult#sync(Object)} internally. A {@code void} method is supported for
 * an operation with no output.
 *
 * <pre>{@code
 * @TemporalOperation
 * public Greeting greet(TemporalNexusClient client, GreetingInput input) {
 *   return new Greeting("Hello, " + input.getName());
 * }
 * }</pre>
 *
 * <p>Access information about the current invocation through {@link Nexus#getInfo()}, including its
 * cancellation token.
 *
 * <p>For custom cancel, or any other handler composition, use {@link OperationImpl} with a {@link
 * TemporalOperationHandler} subclass that overrides {@link
 * TemporalOperationHandler#cancelWorkflowRun}. Both annotations can coexist on the same {@link
 * ServiceImpl} class, but never on the same method.
 */
@Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TemporalOperation {}
