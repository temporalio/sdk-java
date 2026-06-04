package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientImpl;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationExecutionCount;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.common.interceptors.NexusClientCallsInterceptorBase;
import io.temporal.common.interceptors.NexusClientInterceptor;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that user-registered {@link NexusClientInterceptor}s are wrapped around the root invoker
 * in registration order (last registered = outermost), and that every per-call operation passes
 * through every interceptor.
 */
public class NexusClientInterceptorChainTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(PlaceholderWorkflowImpl.class).build();

  @Before
  public void requireStandaloneNexusSupport() {
    assumeTrue(
        "server does not support standalone Nexus operations",
        testWorkflowRule.supportsStandaloneNexusOperations());
  }

  @Test
  public void registeredInterceptorsAreCalledInOrder() {
    List<String> calls = Collections.synchronizedList(new ArrayList<>());
    NexusClientInterceptor first = next -> new RecordingCallsInterceptor("first", next, calls);
    NexusClientInterceptor second = next -> new RecordingCallsInterceptor("second", next, calls);

    NexusClient client =
        NexusClientImpl.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            NexusClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setInterceptors(Arrays.asList(first, second))
                .build());

    // Stream is lazy; consume it to force a single page fetch through the interceptor chain.
    long ignoredListCount = client.listNexusOperationExecutions(null).count();
    NexusOperationExecutionCount ignoredCount = client.countNexusOperationExecutions(null);
    Assert.assertNotNull(ignoredCount);
    Assert.assertTrue(ignoredListCount >= 0);

    // [first, second] -> second wraps first wraps root.
    // A call enters second, descends to first, then root, returns through first then second.
    Assert.assertEquals(
        Arrays.asList(
            "second:list:before",
            "first:list:before",
            "first:list:after",
            "second:list:after",
            "second:count:before",
            "first:count:before",
            "first:count:after",
            "second:count:after"),
        calls);
  }

  static class RecordingCallsInterceptor extends NexusClientCallsInterceptorBase {
    private final String name;
    private final List<String> calls;

    RecordingCallsInterceptor(String name, NexusClientCallsInterceptor next, List<String> calls) {
      super(next);
      this.name = name;
      this.calls = calls;
    }

    @Override
    public ListNexusOperationExecutionsOutput listNexusOperationExecutions(
        ListNexusOperationExecutionsInput input) {
      calls.add(name + ":list:before");
      try {
        return super.listNexusOperationExecutions(input);
      } finally {
        calls.add(name + ":list:after");
      }
    }

    @Override
    public CountNexusOperationExecutionsOutput countNexusOperationExecutions(
        CountNexusOperationExecutionsInput input) {
      calls.add(name + ":count:before");
      try {
        return super.countNexusOperationExecutions(input);
      } finally {
        calls.add(name + ":count:after");
      }
    }
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }
}
