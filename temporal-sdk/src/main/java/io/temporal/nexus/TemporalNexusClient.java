package io.temporal.nexus;

import io.nexusrpc.OperationException;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Experimental;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;

/**
 * Nexus-aware client wrapping {@link WorkflowClient}. Provides methods for interacting with
 * Temporal from within a Nexus operation handler.
 *
 * <p>Obtained via the {@link TemporalOperationHandler.StartHandler} parameter.
 *
 * <p>Example usage to start a workflow from an operation handler:
 *
 * <pre>{@code
 * @OperationImpl
 * public OperationHandler<TransferInput, TransferResult> startTransfer() {
 *   return TemporalOperationHandler.create((context, client, input) -> {
 *     return client.startWorkflow(
 *         TransferWorkflow.class,
 *         TransferWorkflow::transfer, input.getFromAccount(), input.getToAccount(),
 *         WorkflowOptions.newBuilder()
 *             .setWorkflowId("transfer-" + input.getTransferId())
 *             .build());
 *   });
 * }
 * }</pre>
 *
 * <p>For synchronous operations, use {@link #getWorkflowClient()} directly and return a {@link
 * TemporalOperationResult#sync} result. For example, to send a signal:
 *
 * <pre>{@code
 * @OperationImpl
 * public OperationHandler<CancelOrderInput, Void> cancelOrder() {
 *   return TemporalOperationHandler.create((context, client, input) -> {
 *     client.getWorkflowClient()
 *         .newUntypedWorkflowStub("order-" + input.getOrderId())
 *         .signal("requestCancellation", input);
 *     return TemporalOperationResult.sync(null);
 *   });
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

  // draft-review: all these combinations are for 1-1 compatibility - check if they can be trimmed
  /**
   * Starts a workflow update on an existing workflow as a Nexus operation. The result is delivered
   * asynchronously via the Nexus completion callback, unless the update RPC comes back already
   * completed (e.g. a retried request, or a request that failed validation), in which case the
   * result (or failure) is returned synchronously.
   *
   * <p>{@code updateMethod} must be a method reference on a stub created through {@link
   * WorkflowClient#newWorkflowStub(Class, WorkflowOptions)} or {@link
   * WorkflowClient#newWorkflowStub(Class, String)}, targeting the existing workflow to update (as
   * opposed to {@link #startWorkflow}, which creates a new workflow).
   *
   * <p>If set, {@code options}' {@code waitForStage} has to be set to {@code WaitForStage =
   * ACCEPTED} as Nexus Operations only support async Update requests. If unset, will default to
   * {@code WaitForStage = ACCEPTED}. If {@code options} does not set an update ID, it defaults to
   * the Nexus request ID which is consistent with other SDKs usage.
   *
   * <p>A Nexus callback URL is required for this operation; if the caller did not provide one, this
   * method throws a {@code BAD_REQUEST} {@code HandlerException}.
   *
   * <p>Example:
   *
   * <pre>{@code
   * MyWorkflow stub = client.getWorkflowClient().newWorkflowStub(MyWorkflow.class, input.getWorkflowId());
   * client.startWorkflowUpdate(
   *     stub::myUpdate, input.getArg(),
   *     UpdateOptions.newBuilder(String.class).setUpdateName("myUpdate").build())
   * }</pre>
   *
   * @param updateFunc unbound method reference to the update method
   * @param opts update options (must include the update result class)
   * @param <R> the update return type
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <R> TemporalOperationResult<R> startWorkflowUpdate(
      Functions.Func<R> updateFunc, UpdateOptions<R> opts) throws OperationException;

  /**
   * Starts a one-argument workflow update on an existing workflow as a Nexus operation. See {@link
   * #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full behavior contract.
   *
   * @param updateFunc unbound method reference to the update method
   * @param arg1 first update argument
   * @param options update options (must include the update result class)
   * @param <A1> the type of the first update argument
   * @param <R> the update return type
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, R> TemporalOperationResult<R> startWorkflowUpdate(
      Functions.Func1<A1, R> updateFunc, A1 arg1, UpdateOptions<R> options)
      throws OperationException;

  /**
   * Starts a two-argument workflow update on an existing workflow as a Nexus operation. See {@link
   * #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param options update options (must include the update result class)
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <R> the update return type
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, R> TemporalOperationResult<R> startWorkflowUpdate(
      Functions.Func2<A1, A2, R> updateMethod, A1 arg1, A2 arg2, UpdateOptions<R> options)
      throws OperationException;

  /**
   * Starts a three-argument workflow update on an existing workflow as a Nexus operation. See
   * {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param arg3 third update argument
   * @param options update options (must include the update result class)
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <A3> the type of the third update argument
   * @param <R> the update return type
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, A3, R> TemporalOperationResult<R> startWorkflowUpdate(
      Functions.Func3<A1, A2, A3, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      UpdateOptions<R> options)
      throws OperationException;

  /**
   * Starts a four-argument workflow update on an existing workflow as a Nexus operation. See {@link
   * #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param arg3 third update argument
   * @param arg4 fourth update argument
   * @param options update options (must include the update result class)
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <A3> the type of the third update argument
   * @param <A4> the type of the fourth update argument
   * @param <R> the update return type
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, A3, A4, R> TemporalOperationResult<R> startWorkflowUpdate(
      Functions.Func4<A1, A2, A3, A4, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      UpdateOptions<R> options)
      throws OperationException;

  /**
   * Starts a five-argument workflow update on an existing workflow as a Nexus operation. See {@link
   * #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param arg3 third update argument
   * @param arg4 fourth update argument
   * @param arg5 fifth update argument
   * @param options update options (must include the update result class)
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <A3> the type of the third update argument
   * @param <A4> the type of the fourth update argument
   * @param <A5> the type of the fifth update argument
   * @param <R> the update return type
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, A3, A4, A5, R> TemporalOperationResult<R> startWorkflowUpdate(
      Functions.Func5<A1, A2, A3, A4, A5, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      UpdateOptions<R> options)
      throws OperationException;

  /**
   * Starts a six-argument workflow update on an existing workflow as a Nexus operation. See {@link
   * #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param arg3 third update argument
   * @param arg4 fourth update argument
   * @param arg5 fifth update argument
   * @param arg6 sixth update argument
   * @param options update options (must include the update result class)
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <A3> the type of the third update argument
   * @param <A4> the type of the fourth update argument
   * @param <A5> the type of the fifth update argument
   * @param <A6> the type of the sixth update argument
   * @param <R> the update return type
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, A3, A4, A5, A6, R> TemporalOperationResult<R> startWorkflowUpdate(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      UpdateOptions<R> options)
      throws OperationException;

  // draft-review: check if all these are also necessary to preserve 1:1 compatibility

  /**
   * Starts a zero-argument workflow update with no return value on an existing workflow as a Nexus
   * operation. See {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full
   * behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param options update options
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  TemporalOperationResult<Void> startWorkflowUpdate(
      Functions.Proc updateMethod, UpdateOptions<Void> options) throws OperationException;

  /**
   * Starts a one-argument workflow update with no return value on an existing workflow as a Nexus
   * operation. See {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full
   * behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param options update options
   * @param <A1> the type of the first update argument
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1> TemporalOperationResult<Void> startWorkflowUpdate(
      Functions.Proc1<A1> updateMethod, A1 arg1, UpdateOptions<Void> options)
      throws OperationException;

  /**
   * Starts a two-argument workflow update with no return value on an existing workflow as a Nexus
   * operation. See {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full
   * behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param options update options
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2> TemporalOperationResult<Void> startWorkflowUpdate(
      Functions.Proc2<A1, A2> updateMethod, A1 arg1, A2 arg2, UpdateOptions<Void> options)
      throws OperationException;

  /**
   * Starts a three-argument workflow update with no return value on an existing workflow as a Nexus
   * operation. See {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full
   * behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param arg3 third update argument
   * @param options update options
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <A3> the type of the third update argument
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, A3> TemporalOperationResult<Void> startWorkflowUpdate(
      Functions.Proc3<A1, A2, A3> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      UpdateOptions<Void> options)
      throws OperationException;

  /**
   * Starts a four-argument workflow update with no return value on an existing workflow as a Nexus
   * operation. See {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full
   * behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param arg3 third update argument
   * @param arg4 fourth update argument
   * @param options update options
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <A3> the type of the third update argument
   * @param <A4> the type of the fourth update argument
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, A3, A4> TemporalOperationResult<Void> startWorkflowUpdate(
      Functions.Proc4<A1, A2, A3, A4> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      UpdateOptions<Void> options)
      throws OperationException;

  /**
   * Starts a five-argument workflow update with no return value on an existing workflow as a Nexus
   * operation. See {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full
   * behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param arg3 third update argument
   * @param arg4 fourth update argument
   * @param arg5 fifth update argument
   * @param options update options
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <A3> the type of the third update argument
   * @param <A4> the type of the fourth update argument
   * @param <A5> the type of the fifth update argument
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, A3, A4, A5> TemporalOperationResult<Void> startWorkflowUpdate(
      Functions.Proc5<A1, A2, A3, A4, A5> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      UpdateOptions<Void> options)
      throws OperationException;

  /**
   * Starts a six-argument workflow update with no return value on an existing workflow as a Nexus
   * operation. See {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the full
   * behavior contract.
   *
   * @param updateMethod unbound method reference to the update method
   * @param arg1 first update argument
   * @param arg2 second update argument
   * @param arg3 third update argument
   * @param arg4 fourth update argument
   * @param arg5 fifth update argument
   * @param arg6 sixth update argument
   * @param options update options
   * @param <A1> the type of the first update argument
   * @param <A2> the type of the second update argument
   * @param <A3> the type of the third update argument
   * @param <A4> the type of the fourth update argument
   * @param <A5> the type of the fifth update argument
   * @param <A6> the type of the sixth update argument
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <A1, A2, A3, A4, A5, A6> TemporalOperationResult<Void> startWorkflowUpdate(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> updateMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      UpdateOptions<Void> options)
      throws OperationException;

  /**
   * Starts a workflow update using an untyped {@link WorkflowStub}, targeting an existing workflow,
   * as a Nexus operation. See {@link #startWorkflowUpdate(Functions.Func, UpdateOptions)} for the
   * full behavior contract.
   *
   * @param stub untyped stub for the existing workflow to update, created through {@link
   *     WorkflowClient#newUntypedWorkflowStub(String)} or similar
   * @param options update options (must include update name and result class)
   * @param args update arguments
   * @param <R> the update return type
   * @return a {@link TemporalOperationResult}; sync if the update already completed, async
   *     (carrying the update-workflow operation token) otherwise
   */
  <R> TemporalOperationResult<R> startWorkflowUpdate(
      WorkflowStub stub, UpdateOptions<R> options, Object... args) throws OperationException;
}
