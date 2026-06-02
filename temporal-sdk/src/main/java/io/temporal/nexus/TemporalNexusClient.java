package io.temporal.nexus;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.Experimental;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;

/**
 * Nexus-aware client wrapping {@link WorkflowClient}. Provides methods for interacting with
 * Temporal from within a Nexus operation handler.
 *
 * <p>Passed to {@link TemporalOperation}-annotated methods (and {@link
 * TemporalOperationHandler.StartHandler} implementations) alongside the start context and input.
 *
 * <p>Example usage to start a workflow from an operation:
 *
 * <pre>{@code
 * @TemporalOperation
 * public TemporalOperationResult<TransferResult> startTransfer(
 *     TemporalOperationStartContext ctx, TemporalNexusClient client, TransferInput input) {
 *   return client.startWorkflow(
 *       TransferWorkflow.class,
 *       TransferWorkflow::transfer,
 *       input,
 *       WorkflowOptions.newBuilder()
 *           .setWorkflowId("transfer-" + input.getTransferId())
 *           .build());
 * }
 * }</pre>
 *
 * <p>For synchronous operations, use {@link #getWorkflowClient()} directly and return a {@link
 * TemporalOperationResult#sync} result. For example, to send a signal:
 *
 * <pre>{@code
 * @TemporalOperation
 * public TemporalOperationResult<Void> cancelOrder(
 *     TemporalOperationStartContext ctx, TemporalNexusClient client, CancelOrderInput input) {
 *   client.getWorkflowClient()
 *       .newUntypedWorkflowStub("order-" + input.getOrderId())
 *       .signal("requestCancellation", input);
 *   return TemporalOperationResult.sync(null);
 * }
 * }</pre>
 */
@Experimental
public interface TemporalNexusClient {

  /** Returns the underlying {@link WorkflowClient} for advanced use cases. */
  WorkflowClient getWorkflowClient();

  /**
   * Starts a zero-argument workflow that returns a value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::run, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass, Functions.Func1<T, R> workflowMethod, WorkflowOptions options);

  /**
   * Starts a one-argument workflow that returns a value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::processOrder, input, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func2<T, A1, R> workflowMethod,
      A1 arg1,
      WorkflowOptions options);

  /**
   * Starts a two-argument workflow that returns a value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::run, arg1, arg2, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func3<T, A1, A2, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      WorkflowOptions options);

  /**
   * Starts a three-argument workflow that returns a value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::run, arg1, arg2, arg3, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <A3> the type of the third workflow argument
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, A3, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func4<T, A1, A2, A3, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      WorkflowOptions options);

  /**
   * Starts a four-argument workflow that returns a value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::run, arg1, arg2, arg3, arg4, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @param arg4 fourth workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <A3> the type of the third workflow argument
   * @param <A4> the type of the fourth workflow argument
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, A3, A4, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func5<T, A1, A2, A3, A4, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      WorkflowOptions options);

  /**
   * Starts a five-argument workflow that returns a value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::run, arg1, arg2, arg3, arg4, arg5, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @param arg4 fourth workflow argument
   * @param arg5 fifth workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <A3> the type of the third workflow argument
   * @param <A4> the type of the fourth workflow argument
   * @param <A5> the type of the fifth workflow argument
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, A3, A4, A5, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func6<T, A1, A2, A3, A4, A5, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      WorkflowOptions options);

  /**
   * Starts a six-argument workflow that returns a value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::run, arg1, arg2, arg3, arg4, arg5, arg6, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @param arg4 fourth workflow argument
   * @param arg5 fifth workflow argument
   * @param arg6 sixth workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <A3> the type of the third workflow argument
   * @param <A4> the type of the fourth workflow argument
   * @param <A5> the type of the fifth workflow argument
   * @param <A6> the type of the sixth workflow argument
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, A3, A4, A5, A6, R> TemporalOperationResult<R> startWorkflow(
      Class<T> workflowClass,
      Functions.Func7<T, A1, A2, A3, A4, A5, A6, R> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      WorkflowOptions options);

  /**
   * Starts a zero-argument workflow with no return value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::execute, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass, Functions.Proc1<T> workflowMethod, WorkflowOptions options);

  /**
   * Starts a one-argument workflow with no return value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::execute, input, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc2<T, A1> workflowMethod,
      A1 arg1,
      WorkflowOptions options);

  /**
   * Starts a two-argument workflow with no return value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::execute, arg1, arg2, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc3<T, A1, A2> workflowMethod,
      A1 arg1,
      A2 arg2,
      WorkflowOptions options);

  /**
   * Starts a three-argument workflow with no return value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::execute, arg1, arg2, arg3, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <A3> the type of the third workflow argument
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, A3> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc4<T, A1, A2, A3> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      WorkflowOptions options);

  /**
   * Starts a four-argument workflow with no return value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::execute, arg1, arg2, arg3, arg4, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @param arg4 fourth workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <A3> the type of the third workflow argument
   * @param <A4> the type of the fourth workflow argument
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, A3, A4> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc5<T, A1, A2, A3, A4> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      WorkflowOptions options);

  /**
   * Starts a five-argument workflow with no return value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::execute, arg1, arg2, arg3, arg4, arg5, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @param arg4 fourth workflow argument
   * @param arg5 fifth workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <A3> the type of the third workflow argument
   * @param <A4> the type of the fourth workflow argument
   * @param <A5> the type of the fifth workflow argument
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, A3, A4, A5> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc6<T, A1, A2, A3, A4, A5> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      WorkflowOptions options);

  /**
   * Starts a six-argument workflow with no return value.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(MyWorkflow.class, MyWorkflow::execute, arg1, arg2, arg3, arg4, arg5, arg6, options)
   * }</pre>
   *
   * @param workflowClass the workflow interface class
   * @param workflowMethod unbound method reference to the workflow method
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @param arg4 fourth workflow argument
   * @param arg5 fifth workflow argument
   * @param arg6 sixth workflow argument
   * @param options workflow start options (must include workflowId)
   * @param <T> the workflow interface type
   * @param <A1> the type of the first workflow argument
   * @param <A2> the type of the second workflow argument
   * @param <A3> the type of the third workflow argument
   * @param <A4> the type of the fourth workflow argument
   * @param <A5> the type of the fifth workflow argument
   * @param <A6> the type of the sixth workflow argument
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <T, A1, A2, A3, A4, A5, A6> TemporalOperationResult<Void> startWorkflow(
      Class<T> workflowClass,
      Functions.Proc7<T, A1, A2, A3, A4, A5, A6> workflowMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      WorkflowOptions options);

  /**
   * Starts a workflow using an untyped workflow type name.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow("MyWorkflow", String.class, options, input)
   * }</pre>
   *
   * @param workflowType the workflow type name string
   * @param resultClass the expected result class
   * @param options workflow start options (must include workflowId)
   * @param args workflow arguments
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <R> TemporalOperationResult<R> startWorkflow(
      String workflowType, Class<R> resultClass, WorkflowOptions options, Object... args);

  /**
   * Starts a workflow using an untyped workflow type name, with both a result class and a generic
   * {@link Type}. Use this overload when the workflow returns a generic type (e.g. {@code
   * List<String>}) so the result can be deserialized correctly.
   *
   * <p>Example:
   *
   * <pre>{@code
   * client.startWorkflow(
   *     "MyWorkflow",
   *     List.class,
   *     new TypeToken<List<String>>() {}.getType(),
   *     options,
   *     input)
   * }</pre>
   *
   * @param workflowType the workflow type name string
   * @param resultClass the expected result class
   * @param resultType the expected result {@link Type} (carries generic parameters)
   * @param options workflow start options (must include workflowId)
   * @param args workflow arguments
   * @param <R> the workflow return type
   * @return an async {@link TemporalOperationResult} with the workflow-run operation token
   */
  <R> TemporalOperationResult<R> startWorkflow(
      String workflowType,
      Class<R> resultClass,
      Type resultType,
      WorkflowOptions options,
      Object... args);
}
