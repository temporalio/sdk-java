package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.jupiter.api.Test;

public class TestWorkflowEnvironmentActivityClientTest {

  @Test
  public void activityClientDefaultsToWorkflowClientOptions() throws Exception {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setNamespace("workflow-client-namespace")
                    .setIdentity("workflow-client-identity")
                    .build())
            .build();

    try (TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance(options)) {
      ActivityClient activityClient = testEnv.getActivityClient();
      ActivityClientOptions activityClientOptions = getActivityClientOptions(activityClient);

      assertEquals("workflow-client-namespace", activityClientOptions.getNamespace());
      assertEquals("workflow-client-identity", activityClientOptions.getIdentity());
      assertSame(testEnv.getWorkflowServiceStubs(), getWorkflowServiceStubs(activityClient));
    }
  }

  @Test
  public void activityClientUsesExplicitActivityClientOptions() throws Exception {
    ActivityClientInterceptor interceptor = next -> next;
    ActivityClientOptions options =
        ActivityClientOptions.newBuilder()
            .setNamespace("activity-client-namespace")
            .setIdentity("activity-client-identity")
            .setInterceptors(Collections.singletonList(interceptor))
            .build();

    try (TestWorkflowEnvironment testEnv =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setNamespace("workflow-client-namespace")
                        .build())
                .setActivityClientOptions(options)
                .build())) {
      ActivityClient activityClient = testEnv.getActivityClient();
      ActivityClientOptions activityClientOptions = getActivityClientOptions(activityClient);

      assertEquals("activity-client-namespace", activityClientOptions.getNamespace());
      assertEquals("activity-client-identity", activityClientOptions.getIdentity());
      assertSame(interceptor, activityClientOptions.getInterceptors().get(0));
    }
  }

  @Test
  public void testWorkflowRuleExposesActivityClient() throws Exception {
    TestWorkflowRule rule =
        TestWorkflowRule.newBuilder()
            .setActivityClientOptions(
                ActivityClientOptions.newBuilder().setNamespace("rule-activity-namespace").build())
            .build();

    try {
      assertEquals(
          "rule-activity-namespace",
          getActivityClientOptions(rule.getActivityClient()).getNamespace());
    } finally {
      rule.getTestEnvironment().close();
    }
  }

  private static ActivityClientOptions getActivityClientOptions(ActivityClient client)
      throws Exception {
    Field field = client.getClass().getDeclaredField("options");
    field.setAccessible(true);
    return (ActivityClientOptions) field.get(client);
  }

  private static WorkflowServiceStubs getWorkflowServiceStubs(ActivityClient client)
      throws Exception {
    Field field = client.getClass().getDeclaredField("stubs");
    field.setAccessible(true);
    return (WorkflowServiceStubs) field.get(client);
  }
}
