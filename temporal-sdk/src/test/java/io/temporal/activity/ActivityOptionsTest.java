package io.temporal.activity;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.workflow.shared.TestActivities.TestActivity;
import io.temporal.workflow.shared.TestActivities.TestActivityImpl;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.*;
import org.junit.rules.Timeout;

public class ActivityOptionsTest {
  public @Rule Timeout timeout = Timeout.seconds(10);

  private TestActivityEnvironment testEnv;
  private ActivityOptions defaultOps = ActivityTestOptions.newActivityOptions1();
  private final ActivityOptions methodOps1 = ActivityTestOptions.newActivityOptions2();

  @Before
  public void setUp() {
    testEnv = TestActivityEnvironment.newInstance();
  }

  @After
  public void tearDown() throws Exception {
    testEnv.close();
  }

  @MethodRetry(
      initialIntervalSeconds = 12,
      backoffCoefficient = 1.97,
      maximumAttempts = 234567,
      maximumIntervalSeconds = 22,
      doNotRetry = {"java.lang.NullPointerException", "java.lang.UnsupportedOperationException"})
  public void activityAndRetryOptions() {}

  @Test
  public void testActivityOptions() {
    testEnv.registerActivitiesImplementations(new TestActivityImpl());
    try {
      testEnv.newActivityStub(TestActivity.class);
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e instanceof IllegalArgumentException);
      Assert.assertEquals(
          "Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified for Activity1 activity. Please set at least one of the above through the ActivityStub or WorkflowImplementationOptions.",
          e.getMessage());
    }
  }

  @Test
  public void testActivityOptionsMerge() {
    // Assert no changes if no per method options
    ActivityOptions merged =
        ActivityOptions.newBuilder(defaultOps).mergeActivityOptions(null).build();
    Assert.assertEquals(defaultOps, merged);
    // Assert options were overridden with method options
    merged = ActivityOptions.newBuilder(defaultOps).mergeActivityOptions(methodOps1).build();
    Assert.assertEquals(methodOps1, merged);
  }

  @Test
  public void testActivityOptionsMergeWithImmutableContextPropagators() {
    // Local class to avoid code duplication
    class TestContextPropagator implements ContextPropagator {
      private final String name;

      TestContextPropagator(String name) {
        this.name = name;
      }

      @Override
      public String getName() {
        return name;
      }

      @Override
      public Map<String, Payload> serializeContext(Object context) {
        return Collections.emptyMap();
      }

      @Override
      public Object deserializeContext(Map<String, Payload> context) {
        return null;
      }

      @Override
      public Object getCurrentContext() {
        return null;
      }

      @Override
      public void setCurrentContext(Object context) {}
    }

    ContextPropagator propagator1 = new TestContextPropagator("propagator1");
    ContextPropagator propagator2 = new TestContextPropagator("propagator2");

    // Create options with immutable singleton lists
    // This tests the fix for https://github.com/temporalio/sdk-java/issues/2482
    ActivityOptions options1 =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(1))
            .setContextPropagators(Collections.singletonList(propagator1))
            .build();

    ActivityOptions options2 =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(2))
            .setContextPropagators(Collections.singletonList(propagator2))
            .build();

    // Merging should not throw UnsupportedOperationException
    ActivityOptions merged =
        ActivityOptions.newBuilder(options1).mergeActivityOptions(options2).build();

    // Verify both context propagators are present in the merged result
    List<ContextPropagator> mergedPropagators = merged.getContextPropagators();
    assertNotNull(mergedPropagators);
    assertEquals(2, mergedPropagators.size());
    assertEquals("propagator1", mergedPropagators.get(0).getName());
    assertEquals("propagator2", mergedPropagators.get(1).getName());
  }

  @Test
  public void testActivityOptionsDefaultInstance() {
    testEnv.registerActivitiesImplementations(new TestActivityImpl());
    TestActivity activity =
        testEnv.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofDays(1)).build());

    // Check that options were set correctly
    Map<String, Duration> optionsValues = activity.activity1();
    Assert.assertEquals(Duration.ofDays(1), optionsValues.get("ScheduleToCloseTimeout"));
  }

  @Test
  public void testOnlyAnnotationsPresent() throws NoSuchMethodException {
    Method method = ActivityOptionsTest.class.getMethod("activityAndRetryOptions");
    ActivityMethod a = method.getAnnotation(ActivityMethod.class);
    MethodRetry r = method.getAnnotation(MethodRetry.class);
    ActivityOptions o = ActivityOptions.newBuilder().build();
    ActivityOptions merged = ActivityOptions.newBuilder(o).mergeMethodRetry(r).build();

    RetryOptions rMerged = merged.getRetryOptions();
    assertEquals(r.maximumAttempts(), rMerged.getMaximumAttempts());
    assertEquals(r.backoffCoefficient(), rMerged.getBackoffCoefficient(), 0.0);
    assertEquals(Duration.ofSeconds(r.initialIntervalSeconds()), rMerged.getInitialInterval());
    assertEquals(Duration.ofSeconds(r.maximumIntervalSeconds()), rMerged.getMaximumInterval());
    Assert.assertArrayEquals(r.doNotRetry(), rMerged.getDoNotRetry());
  }

  @Test
  public void testLocalActivityOptions() {
    try {
      LocalActivityOptions.newBuilder().validateAndBuildWithDefaults();
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("scheduleToCloseTimeout"));
    }
    assertEquals(
        Duration.ofSeconds(10),
        LocalActivityOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofSeconds(10))
            .validateAndBuildWithDefaults()
            .getScheduleToCloseTimeout());

    assertEquals(
        Duration.ofSeconds(11),
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(11))
            .validateAndBuildWithDefaults()
            .getStartToCloseTimeout());

    RetryOptions retryOptions =
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(11))
            .validateAndBuildWithDefaults()
            .getRetryOptions();
    assertNotNull(retryOptions);
    assertEquals(2.0, retryOptions.getBackoffCoefficient(), 0e-5);
    assertEquals(Duration.ofSeconds(1), retryOptions.getInitialInterval());
    assertNull(retryOptions.getMaximumInterval());
  }
}
