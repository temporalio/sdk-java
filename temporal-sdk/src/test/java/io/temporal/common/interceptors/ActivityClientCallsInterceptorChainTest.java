package io.temporal.common.interceptors;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.client.StartActivityOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Tests for the {@link ActivityClientInterceptor} factory pattern and chain-building behavior. */
public class ActivityClientCallsInterceptorChainTest {

  private static StartActivityInput minimalInput() {
    return new StartActivityInput(
        "MyActivity",
        Collections.emptyList(),
        StartActivityOptions.newBuilder()
            .setId("act-id")
            .setTaskQueue("tq")
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build(),
        Header.empty());
  }

  /**
   * Builds a chain from a list of interceptors and a root, replicating ActivityClientImpl logic.
   */
  private static ActivityClientCallsInterceptor buildChain(
      List<ActivityClientInterceptor> interceptors, ActivityClientCallsInterceptor root) {
    ActivityClientCallsInterceptor invoker = root;
    for (ActivityClientInterceptor interceptor : interceptors) {
      invoker = interceptor.activityClientCallsInterceptor(invoker);
    }
    return invoker;
  }

  // ---- Chain ordering ----

  @Test
  public void testSingleInterceptorExecutesBeforeRoot() {
    List<String> events = new ArrayList<>();
    ActivityClientCallsInterceptor root = mock(ActivityClientCallsInterceptor.class);
    when(root.startActivity(any()))
        .thenAnswer(
            inv -> {
              events.add("root");
              return new StartActivityOutput("id", null);
            });

    ActivityClientInterceptor interceptor =
        new ActivityClientInterceptorBase() {
          @Override
          public ActivityClientCallsInterceptor activityClientCallsInterceptor(
              ActivityClientCallsInterceptor next) {
            return new ActivityClientCallsInterceptorBase(next) {
              @Override
              public StartActivityOutput startActivity(StartActivityInput input) {
                events.add("A");
                return super.startActivity(input);
              }
            };
          }
        };

    ActivityClientCallsInterceptor chain = buildChain(Collections.singletonList(interceptor), root);
    chain.startActivity(minimalInput());

    assertEquals(Arrays.asList("A", "root"), events);
  }

  @Test
  public void testTwoInterceptorsLastIsOutermost() {
    List<String> events = new ArrayList<>();
    ActivityClientCallsInterceptor root = mock(ActivityClientCallsInterceptor.class);
    when(root.startActivity(any()))
        .thenAnswer(
            inv -> {
              events.add("root");
              return new StartActivityOutput("id", null);
            });

    ActivityClientInterceptor first = factoryInterceptor("A", events);
    ActivityClientInterceptor second = factoryInterceptor("B", events);

    ActivityClientCallsInterceptor chain = buildChain(Arrays.asList(first, second), root);
    chain.startActivity(minimalInput());

    assertEquals(Arrays.asList("B", "A", "root"), events);
  }

  @Test
  public void testThreeInterceptorsLastIsOutermost() {
    List<String> events = new ArrayList<>();
    ActivityClientCallsInterceptor root = mock(ActivityClientCallsInterceptor.class);
    when(root.startActivity(any()))
        .thenAnswer(
            inv -> {
              events.add("root");
              return new StartActivityOutput("id", null);
            });

    ActivityClientCallsInterceptor chain =
        buildChain(
            Arrays.asList(
                factoryInterceptor("A", events),
                factoryInterceptor("B", events),
                factoryInterceptor("C", events)),
            root);
    chain.startActivity(minimalInput());

    assertEquals(Arrays.asList("C", "B", "A", "root"), events);
  }

  // ---- ActivityClientInterceptorBase defaults ----

  @Test
  public void testActivityClientInterceptorBaseDefaultPassesThrough() {
    // ActivityClientInterceptorBase.activityClientCallsInterceptor returns next unchanged.
    ActivityClientCallsInterceptor root = mock(ActivityClientCallsInterceptor.class);
    when(root.startActivity(any())).thenReturn(new StartActivityOutput("id", null));

    ActivityClientInterceptor passthrough = new ActivityClientInterceptorBase() {};

    ActivityClientCallsInterceptor chain = buildChain(Collections.singletonList(passthrough), root);
    StartActivityOutput output = chain.startActivity(minimalInput());

    assertNotNull(output);
    verify(root).startActivity(any());
  }

  @Test
  public void testInterceptorBaseCanWrapAndInterceptCalls() {
    List<String> events = new ArrayList<>();
    ActivityClientCallsInterceptor root = mock(ActivityClientCallsInterceptor.class);
    when(root.startActivity(any()))
        .thenAnswer(
            inv -> {
              events.add("root");
              return new StartActivityOutput("id", null);
            });

    ActivityClientInterceptor factory =
        new ActivityClientInterceptorBase() {
          @Override
          public ActivityClientCallsInterceptor activityClientCallsInterceptor(
              ActivityClientCallsInterceptor next) {
            return new ActivityClientCallsInterceptorBase(next) {
              @Override
              public StartActivityOutput startActivity(StartActivityInput input) {
                events.add("intercepted");
                return super.startActivity(input);
              }
            };
          }
        };

    buildChain(Collections.singletonList(factory), root).startActivity(minimalInput());

    assertEquals(Arrays.asList("intercepted", "root"), events);
  }

  // ---- Helper ----

  private static ActivityClientInterceptor factoryInterceptor(String name, List<String> events) {
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
