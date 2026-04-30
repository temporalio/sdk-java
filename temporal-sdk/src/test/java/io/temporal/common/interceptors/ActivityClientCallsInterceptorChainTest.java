package io.temporal.common.interceptors;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for interceptor chain ordering using a real Temporal server. Each test creates an {@link
 * ActivityClient} with two interceptors and verifies the call order through a client method.
 */
public class ActivityClientCallsInterceptorChainTest {

  @ActivityInterface
  public interface EchoActivity {
    @ActivityMethod(name = "ChainTestEcho")
    String echo(String input);
  }

  static class EchoActivityImpl implements EchoActivity {
    @Override
    public String echo(String input) {
      return input;
    }
  }

  @Rule
  public SDKTestWorkflowRule testRule =
      SDKTestWorkflowRule.newBuilder().setActivityImplementations(new EchoActivityImpl()).build();

  @Test
  public void testTwoInterceptorsCalledInOrderOnStart() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    List<String> events = new ArrayList<>();

    ActivityClient client = newClient(startInterceptor("A", events), startInterceptor("B", events));
    String result = client.execute(EchoActivity.class, EchoActivity::echo, opts(uniqueId()), "hi");

    assertEquals("hi", result);
    // B is last in the list → B wraps A → B is outermost → B called first
    assertEquals(Arrays.asList("B", "A"), events);
  }

  @Test
  public void testInterceptorListOrderDeterminesCallOrder() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    List<String> events = new ArrayList<>();

    // reversed list order: A is last → A wraps B → A is outermost → A called first
    ActivityClient client = newClient(startInterceptor("B", events), startInterceptor("A", events));
    client.execute(EchoActivity.class, EchoActivity::echo, opts(uniqueId()), "hi");

    assertEquals(Arrays.asList("A", "B"), events);
  }

  // ---- Helpers ----

  private ActivityClient newClient(ActivityClientInterceptor... interceptors) {
    return ActivityClient.newInstance(
        testRule.getWorkflowServiceStubs(),
        ActivityClientOptions.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setInterceptors(Arrays.asList(interceptors))
            .build());
  }

  private String uniqueId() {
    return "act-" + UUID.randomUUID();
  }

  private StartActivityOptions opts(String id) {
    return StartActivityOptions.newBuilder()
        .setId(id)
        .setTaskQueue(testRule.getTaskQueue())
        .setScheduleToCloseTimeout(Duration.ofMinutes(1))
        .build();
  }

  private static ActivityClientInterceptor startInterceptor(String name, List<String> events) {
    return new ActivityClientInterceptorBase() {
      @Override
      public ActivityClientCallsInterceptor activityClientCallsInterceptor(
          ActivityClientCallsInterceptor next) {
        return new ActivityClientCallsInterceptorBase(next) {
          @Override
          public StartActivityOutput startActivity(StartActivityInput input) {
            events.add(name);
            return super.startActivity(input);
          }
        };
      }
    };
  }
}
