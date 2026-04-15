package io.temporal.common.interceptors;

import io.temporal.client.*;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.List;
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

  StartActivityOutput startActivity(StartActivityInput input)
      throws ActivityAlreadyStartedException;

  <R> GetActivityResultOutput<R> getActivityResult(GetActivityResultInput<R> input)
      throws ActivityFailedException;

  DescribeActivityOutput describeActivity(DescribeActivityInput input);

  CancelActivityOutput cancelActivity(CancelActivityInput input);

  TerminateActivityOutput terminateActivity(TerminateActivityInput input);

  ListActivitiesOutput listActivities(ListActivitiesInput input);

  ListActivitiesPaginatedOutput listActivitiesPaginated(ListActivitiesPaginatedInput input);

  CountActivitiesOutput countActivities(CountActivitiesInput input);

  // ---- Input/Output classes ----

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

    public GetActivityResultInput(
        String activityId,
        @Nullable String runId,
        Class<R> resultClass,
        @Nullable Type resultType) {
      this.activityId = activityId;
      this.runId = runId;
      this.resultClass = resultClass;
      this.resultType = resultType;
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
    private final ActivityDescribeOptions options;

    public DescribeActivityInput(
        String id, @Nullable String runId, ActivityDescribeOptions options) {
      this.id = id;
      this.runId = runId;
      this.options = options;
    }

    public String getId() {
      return id;
    }

    @Nullable
    public String getRunId() {
      return runId;
    }

    public ActivityDescribeOptions getOptions() {
      return options;
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
    private final ActivityCancelOptions options;

    public CancelActivityInput(String id, @Nullable String runId, ActivityCancelOptions options) {
      this.id = id;
      this.runId = runId;
      this.options = options;
    }

    public String getId() {
      return id;
    }

    @Nullable
    public String getRunId() {
      return runId;
    }

    public ActivityCancelOptions getOptions() {
      return options;
    }
  }

  @Experimental
  final class CancelActivityOutput {}

  @Experimental
  final class TerminateActivityInput {
    private final String id;
    private final @Nullable String runId;
    private final @Nullable String reason;
    private final ActivityTerminateOptions options;

    public TerminateActivityInput(
        String id,
        @Nullable String runId,
        @Nullable String reason,
        ActivityTerminateOptions options) {
      this.id = id;
      this.runId = runId;
      this.reason = reason;
      this.options = options;
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

    public ActivityTerminateOptions getOptions() {
      return options;
    }
  }

  @Experimental
  final class TerminateActivityOutput {}

  @Experimental
  final class ListActivitiesInput {
    private final String query;
    private final ActivityListOptions options;

    public ListActivitiesInput(String query, ActivityListOptions options) {
      this.query = query;
      this.options = options;
    }

    public String getQuery() {
      return query;
    }

    public ActivityListOptions getOptions() {
      return options;
    }
  }

  @Experimental
  final class ListActivitiesOutput {
    private final Stream<ActivityExecution> stream;

    public ListActivitiesOutput(Stream<ActivityExecution> stream) {
      this.stream = stream;
    }

    public Stream<ActivityExecution> getStream() {
      return stream;
    }
  }

  @Experimental
  final class ListActivitiesPaginatedInput {
    private final String query;
    private final @Nullable byte[] nextPageToken;
    private final ActivityListPaginatedOptions options;

    public ListActivitiesPaginatedInput(
        String query, @Nullable byte[] nextPageToken, ActivityListPaginatedOptions options) {
      this.query = query;
      this.nextPageToken = nextPageToken;
      this.options = options;
    }

    public String getQuery() {
      return query;
    }

    @Nullable
    public byte[] getNextPageToken() {
      return nextPageToken;
    }

    public ActivityListPaginatedOptions getOptions() {
      return options;
    }
  }

  @Experimental
  final class ListActivitiesPaginatedOutput {
    private final ActivityListPage page;

    public ListActivitiesPaginatedOutput(ActivityListPage page) {
      this.page = page;
    }

    public ActivityListPage getPage() {
      return page;
    }
  }

  @Experimental
  final class CountActivitiesInput {
    private final String query;
    private final ActivityCountOptions options;

    public CountActivitiesInput(String query, ActivityCountOptions options) {
      this.query = query;
      this.options = options;
    }

    public String getQuery() {
      return query;
    }

    public ActivityCountOptions getOptions() {
      return options;
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
