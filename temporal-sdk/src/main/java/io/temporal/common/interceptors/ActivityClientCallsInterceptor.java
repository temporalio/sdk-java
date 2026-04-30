package io.temporal.common.interceptors;

import io.temporal.client.ActivityAlreadyStartedException;
import io.temporal.client.ActivityExecutionCount;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityExecutionMetadata;
import io.temporal.client.ActivityFailedException;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Intercepts calls to the {@link io.temporal.client.ActivityClient} related to the lifecycle of a
 * standalone Activity.
 *
 * <p>Prefer extending {@link ActivityClientCallsInterceptorBase} and overriding only the methods
 * you need instead of implementing this interface directly. {@link
 * ActivityClientCallsInterceptorBase} provides correct default implementations to all the methods
 * of this interface.
 */
@Experimental
public interface ActivityClientCallsInterceptor {

  /**
   * Schedules a standalone activity for execution. The activity type name, arguments, and
   * scheduling options are carried in {@code input}.
   *
   * @param input activity type, raw arguments, scheduling options, and propagated header
   * @return output containing the caller-supplied activity ID and the run ID assigned by the server
   *     (if the server provides one)
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  StartActivityOutput startActivity(StartActivityInput input);

  /**
   * Synchronously long-polls the server until the activity completes, then deserializes and returns
   * the result. Loops automatically on empty long-poll responses (server-side timeout with no
   * outcome yet). Blocks the calling thread for the duration.
   *
   * @param input activity ID, optional run ID, and result class and generic type
   * @param <R> the expected result type
   * @return output wrapping the deserialized activity result
   * @throws ActivityFailedException if the activity failed or was cancelled
   */
  <R> GetActivityResultOutput<R> getActivityResult(GetActivityResultInput<R> input)
      throws TimeoutException;

  /**
   * Returns the current execution description for a standalone activity. If a long-poll token from
   * a prior call is present in {@code input}, the server blocks until the description changes or
   * the poll times out, enabling efficient change detection.
   *
   * @param input activity ID, optional run ID, and optional long-poll token from a prior call
   * @return output containing the {@link ActivityExecutionDescription}; the fresh long-poll token
   *     for the next call is stored inside the returned {@link ActivityExecutionDescription}
   */
  DescribeActivityOutput describeActivity(DescribeActivityInput input);

  /**
   * Requests cooperative cancellation of a running standalone activity. The activity will receive a
   * cancellation request on its next heartbeat; it may choose to honour or ignore it.
   *
   * @param input activity ID, optional run ID, and optional human-readable cancellation reason
   * @return an empty output object (reserved for future use)
   */
  CancelActivityOutput cancelActivity(CancelActivityInput input);

  /**
   * Forcefully terminates a running standalone activity. Unlike cancellation, termination is
   * immediate and cannot be caught or suppressed by the activity.
   *
   * @param input activity ID, optional run ID, and optional human-readable termination reason
   * @return an empty output object (reserved for future use)
   */
  TerminateActivityOutput terminateActivity(TerminateActivityInput input);

  /**
   * Returns a lazy {@link java.util.stream.Stream} of activity execution metadata matching the
   * Visibility query in {@code input}. Pages are fetched from the server on demand as the stream is
   * consumed.
   *
   * @param input Visibility query string and listing options (e.g. page-size limit)
   * @return output wrapping a stream of {@link ActivityExecutionMetadata}
   */
  ListActivitiesOutput listActivities(ListActivitiesInput input);

  /**
   * Returns the count of activity executions matching the Visibility query in {@code input}.
   *
   * @param input Visibility query string and count options
   * @return output wrapping the {@link ActivityExecutionCount}
   */
  CountActivitiesOutput countActivities(CountActivitiesInput input);

  /**
   * Asynchronously polls for the activity result without blocking the calling thread. Returns a
   * {@link CompletableFuture} that completes with the deserialized result when the activity
   * succeeds, or completes exceptionally with {@link ActivityFailedException} on failure, or {@link
   * java.util.concurrent.TimeoutException} if the deadline in {@code input} expires before the
   * activity completes.
   *
   * <p>The default implementation wraps {@link #getActivityResult} in a {@link
   * CompletableFuture#supplyAsync} call. Interceptors that want true non-blocking behavior should
   * override this method.
   *
   * @param input activity ID, optional run ID, result class and generic type, and timeout
   * @param <R> the expected result type
   * @return a future that completes with output wrapping the deserialized activity result
   */
  <R> CompletableFuture<GetActivityResultOutput<R>> getActivityResultAsync(
      GetActivityResultInput<R> input);

  @Experimental
  final class StartActivityInput {
    private final String activityType;
    private final List<Object> args;
    private final StartActivityOptions options;
    private final Header header;

    public StartActivityInput(
        String activityType, List<Object> args, StartActivityOptions options, Header header) {
      this.activityType = activityType;
      this.args = args;
      this.options = options;
      this.header = header;
    }

    public String getActivityType() {
      return activityType;
    }

    public List<Object> getArgs() {
      return args;
    }

    public StartActivityOptions getOptions() {
      return options;
    }

    public Header getHeader() {
      return header;
    }
  }

  @Experimental
  final class StartActivityOutput {
    private final String activityId;
    private final @Nullable String activityRunId;

    public StartActivityOutput(String activityId, @Nullable String activityRunId) {
      this.activityId = activityId;
      this.activityRunId = activityRunId;
    }

    public String getActivityId() {
      return activityId;
    }

    @Nullable
    public String getActivityRunId() {
      return activityRunId;
    }
  }

  @Experimental
  final class GetActivityResultInput<R> {
    private final String activityId;
    private final @Nullable String runId;
    private final Class<R> resultClass;
    private final @Nullable Type resultType;
    private final long timeout;
    private final TimeUnit timeoutUnit;

    public GetActivityResultInput(
        String activityId,
        @Nullable String runId,
        Class<R> resultClass,
        @Nullable Type resultType,
        long timeout,
        TimeUnit timeoutUnit) {
      this.activityId = activityId;
      this.runId = runId;
      this.resultClass = resultClass;
      this.resultType = resultType;
      this.timeout = timeout;
      this.timeoutUnit = timeoutUnit;
    }

    /** No-timeout constructor: waits indefinitely. */
    public GetActivityResultInput(
        String activityId,
        @Nullable String runId,
        Class<R> resultClass,
        @Nullable Type resultType) {
      this(activityId, runId, resultClass, resultType, Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    /** Backward-compatible constructor that passes {@code null} for {@code resultType}. */
    public GetActivityResultInput(String activityId, @Nullable String runId, Class<R> resultClass) {
      this(activityId, runId, resultClass, null);
    }

    public String getActivityId() {
      return activityId;
    }

    @Nullable
    public String getRunId() {
      return runId;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    @Nullable
    public Type getResultType() {
      return resultType;
    }

    public long getTimeout() {
      return timeout;
    }

    public TimeUnit getTimeoutUnit() {
      return timeoutUnit;
    }
  }

  @Experimental
  final class GetActivityResultOutput<R> {
    private final R result;

    public GetActivityResultOutput(R result) {
      this.result = result;
    }

    public R getResult() {
      return result;
    }
  }

  @Experimental
  final class DescribeActivityInput {
    private final String id;
    private final @Nullable String runId;

    public DescribeActivityInput(String id, @Nullable String runId) {
      this.id = id;
      this.runId = runId;
    }

    public String getId() {
      return id;
    }

    @Nullable
    public String getRunId() {
      return runId;
    }
  }

  @Experimental
  final class DescribeActivityOutput {
    private final ActivityExecutionDescription description;

    public DescribeActivityOutput(ActivityExecutionDescription description) {
      this.description = description;
    }

    public ActivityExecutionDescription getDescription() {
      return description;
    }
  }

  @Experimental
  final class CancelActivityInput {
    private final String id;
    private final @Nullable String runId;
    private final @Nullable String reason;

    public CancelActivityInput(String id, @Nullable String runId, @Nullable String reason) {
      this.id = id;
      this.runId = runId;
      this.reason = reason;
    }

    public String getId() {
      return id;
    }

    @Nullable
    public String getRunId() {
      return runId;
    }

    @Nullable
    public String getReason() {
      return reason;
    }
  }

  @Experimental
  final class CancelActivityOutput {}

  @Experimental
  final class TerminateActivityInput {
    private final String id;
    private final @Nullable String runId;
    private final @Nullable String reason;

    public TerminateActivityInput(String id, @Nullable String runId, @Nullable String reason) {
      this.id = id;
      this.runId = runId;
      this.reason = reason;
    }

    public String getId() {
      return id;
    }

    @Nullable
    public String getRunId() {
      return runId;
    }

    @Nullable
    public String getReason() {
      return reason;
    }
  }

  @Experimental
  final class TerminateActivityOutput {}

  @Experimental
  final class ListActivitiesInput {
    private final String query;

    public ListActivitiesInput(String query) {
      this.query = query;
    }

    public String getQuery() {
      return query;
    }
  }

  @Experimental
  final class ListActivitiesOutput {
    private final Stream<ActivityExecutionMetadata> stream;

    public ListActivitiesOutput(Stream<ActivityExecutionMetadata> stream) {
      this.stream = stream;
    }

    public Stream<ActivityExecutionMetadata> getStream() {
      return stream;
    }
  }

  @Experimental
  final class CountActivitiesInput {
    private final String query;

    public CountActivitiesInput(String query) {
      this.query = query;
    }

    public String getQuery() {
      return query;
    }
  }

  @Experimental
  final class CountActivitiesOutput {
    private final ActivityExecutionCount count;

    public CountActivitiesOutput(ActivityExecutionCount count) {
      this.count = count;
    }

    public ActivityExecutionCount getCount() {
      return count;
    }
  }
}
