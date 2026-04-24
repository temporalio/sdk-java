package io.temporal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.workflow.Functions;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;

public class ActivityClientImplTest {

  @ActivityInterface
  public interface ProbedActivity {
    @ActivityMethod(name = "DoIt")
    void doIt();
  }

  @ActivityInterface
  public interface WrongActivity {
    @ActivityMethod(name = "DoWrong")
    void doWrong();
  }

  private ActivityClient client;
  private StartActivityOptions options;

  @Before
  public void setUp() {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    WorkflowServiceStubsOptions stubsOptions = mock(WorkflowServiceStubsOptions.class);
    Scope scope = mock(Scope.class);
    when(stubs.getOptions()).thenReturn(stubsOptions);
    when(stubsOptions.getMetricsScope()).thenReturn(scope);
    when(scope.tagged(any())).thenReturn(scope);

    client = ActivityClient.newInstance(stubs);
    options =
        StartActivityOptions.newBuilder()
            .setId("test-id")
            .setTaskQueue("test-queue")
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();
  }

  @Test(expected = NoSuchMethodError.class)
  @SuppressWarnings("unchecked")
  public void testStartWithMethodFromWrongClass() {
    Functions.Proc1<ProbedActivity> wrongRef =
        (Functions.Proc1<ProbedActivity>)
            (Object) (Functions.Proc1<WrongActivity>) WrongActivity::doWrong;
    client.start(ProbedActivity.class, wrongRef, options);
  }

  @Test(expected = NoSuchMethodError.class)
  public void testStartWithLambdaThatIgnoresProbe() {
    client.start(ProbedActivity.class, ignored -> {}, options);
  }
}
