package io.temporal.client;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptorBase;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import io.temporal.common.interceptors.Header;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
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

  private WorkflowServiceStubs stubs;
  private ActivityClient client;
  private StartActivityOptions options;

  @Before
  public void setUp() {
    stubs = mock(WorkflowServiceStubs.class);
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

  @Test
  public void testContextPropagatorHeaderIsIncluded() {
    Payload payload = Payload.newBuilder().build();
    ContextPropagator propagator = mock(ContextPropagator.class);
    when(propagator.getCurrentContext()).thenReturn("ctx");
    when(propagator.serializeContext("ctx"))
        .thenReturn(Collections.singletonMap("my-key", payload));

    AtomicReference<Header> capturedHeader = new AtomicReference<>();
    ActivityClientInterceptor capturingInterceptor =
        next ->
            new ActivityClientCallsInterceptorBase(next) {
              @Override
              public ActivityClientCallsInterceptor.StartActivityOutput startActivity(
                  ActivityClientCallsInterceptor.StartActivityInput input) {
                capturedHeader.set(input.getHeader());
                return new ActivityClientCallsInterceptor.StartActivityOutput("fake-id", null);
              }
            };

    ActivityClientOptions opts =
        ActivityClientOptions.newBuilder()
            .setContextPropagators(Collections.singletonList(propagator))
            .setInterceptors(Collections.singletonList(capturingInterceptor))
            .build();

    ActivityClient clientWithPropagators = ActivityClient.newInstance(stubs, opts);
    clientWithPropagators.start(ProbedActivity.class, ProbedActivity::doIt, options);

    assertNotNull(capturedHeader.get());
    assertEquals(payload, capturedHeader.get().getValues().get("my-key"));
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
