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
 * <p>The method must:
 *
 * <ul>
 *   <li>be {@code public},
 *   <li>accept exactly three parameters: {@link TemporalOperationStartContext}, {@link
 *       TemporalNexusClient}, and the operation input type,
 *   <li>return {@link TemporalOperationResult}.
 * </ul>
 *
 * <p>Workflow-run example:
 *
 * <pre>{@code
 * @ServiceImpl(service = TransferService.class)
 * public class TransferServiceImpl {
 *   @TemporalOperation
 *   public TemporalOperationResult<TransferResult> transfer(
 *       TemporalOperationStartContext ctx, TemporalNexusClient client, TransferInput input) {
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
