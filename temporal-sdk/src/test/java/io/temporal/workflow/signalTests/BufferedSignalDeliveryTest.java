package io.temporal.workflow.signalTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.DynamicSignalHandler;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class BufferedSignalDeliveryTest {
  private final List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @After
  public void restoreFlags() {
    WorkflowStateMachines.initialFlags = savedInitialFlags;
  }

  @Test
  public void matchingThenUnrelatedListener() {
    runScenario("named", "a:one", false);
  }

  @Test
  public void namedThenDynamicListener() {
    runScenario("dynamic", "a:one,dynamic:b:two", false);
  }

  @Test
  public void unrelatedThenMatchingListener() {
    runScenario("unmatched", "a:one", false);
  }

  @Test
  public void legacyMatchingThenUnrelatedListener() {
    runScenario("named", "a:one,a:one", true);
  }

  @Test
  public void legacyNamedThenDynamicListener() {
    runScenario("dynamic", "a:one,dynamic:a:one,dynamic:b:two,dynamic:b:two", true);
  }

  @Test
  public void legacyUnrelatedThenMatchingListener() {
    runScenario("unmatched", "a:one,a:one", true);
  }

  @Test
  public void immediateDeliveryPrefersNamedHandlers() {
    runScenario("immediate", "a:one,b:two,dynamic:other:three", false);
  }

  @Test
  public void legacyImmediateDeliveryPrefersNamedHandlers() {
    runScenario("immediate", "a:one,b:two,dynamic:other:three", true);
  }

  @Test
  public void unmatchedSignalsKeepTheirOrder() {
    runScenario("pending", "a:one,a:three,b:two,b:four", false);
  }

  @Test
  public void nestedRegistrationCanDeliverAnotherReadySignal() {
    runScenario("nested", "a:start,b:two,a:end", false);
  }

  @Test
  public void blockedHandlerCanWaitForAnotherRegistration() {
    runScenario("blocked", "a:start,b:two,a:end", false);
  }

  @Test
  public void dynamicHandlerCanRegisterNamedListener() {
    runScenario("dynamicNested", "dynamic:a:one,a:three", false);
  }

  private void runScenario(String scenario, String expected, boolean legacy) {
    List<SdkFlag> flags = new ArrayList<>(savedInitialFlags);
    flags.remove(SdkFlag.CONSUME_BUFFERED_SIGNALS);
    if (!legacy) {
      flags.add(SdkFlag.CONSUME_BUFFERED_SIGNALS);
    }
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(flags);
    TestWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    WorkflowClient.start(workflow::execute, scenario);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    if (scenario.equals("immediate")) {
      assertTrue(workflow.ready());
    }
    stub.signal("a", "one");
    if (scenario.equals("dynamic")
        || scenario.equals("pending")
        || scenario.equals("nested")
        || scenario.equals("blocked")
        || scenario.equals("immediate")) {
      stub.signal("b", "two");
    }
    if (scenario.equals("pending") || scenario.equals("dynamicNested")) {
      stub.signal("a", "three");
    }
    if (scenario.equals("pending")) {
      stub.signal("b", "four");
    }
    if (scenario.equals("immediate")) {
      stub.signal("other", "three");
    }
    workflow.proceed();
    String result = stub.getResult(String.class);
    WorkflowExecutionHistory history =
        testWorkflowRule.getWorkflowClient().fetchHistory(stub.getExecution().getWorkflowId());
    assertEquals(expected, result);
    assertEquals(
        !legacy,
        history.getEvents().stream()
            .anyMatch(
                event ->
                    event
                        .getWorkflowTaskCompletedEventAttributes()
                        .getSdkMetadata()
                        .getLangUsedFlagsList()
                        .contains(SdkFlag.CONSUME_BUFFERED_SIGNALS.getValue())));
    assertEquals(
        expected.split(",").length,
        history.getEvents().stream()
            .filter(event -> event.getEventType() == EventType.EVENT_TYPE_TIMER_STARTED)
            .count());
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(String scenario);

    @SignalMethod
    void proceed();

    @QueryMethod
    boolean ready();
  }

  public interface AListener {
    @SignalMethod
    void a(String value);
  }

  public interface BListener {
    @SignalMethod
    void b(String value);
  }

  public interface CListener {
    @SignalMethod
    void c(String value);
  }

  public interface ABListener extends AListener, BListener {}

  public static class TestWorkflowImpl implements TestWorkflow {
    private boolean proceed;
    private boolean ready;
    private boolean bReceived;
    private final List<String> deliveries = new ArrayList<>();

    @Override
    public String execute(String scenario) {
      if (scenario.equals("immediate")) {
        registerA();
        registerB();
        registerDynamic();
        ready = true;
      }
      Workflow.await(() -> proceed);
      if (scenario.equals("immediate")) {
        Workflow.await(Workflow::isEveryHandlerFinished);
        registerC();
      } else if (scenario.equals("nested") || scenario.equals("blocked")) {
        Workflow.registerListener(
            new ABListener() {
              @Override
              public void a(String value) {
                record("a:start");
                if (scenario.equals("blocked")) {
                  Async.procedure(() -> registerC());
                } else {
                  registerC();
                }
                Workflow.await(() -> bReceived);
                record("a:end");
              }

              @Override
              public void b(String value) {
                record("b:" + value);
                bReceived = true;
              }
            });
      } else if (scenario.equals("dynamicNested")) {
        Workflow.registerListener(
            (DynamicSignalHandler)
                (name, values) -> {
                  record("dynamic:" + name + ":" + values.get(0, String.class));
                  registerA();
                });
      } else if (scenario.equals("unmatched")) {
        registerB();
        registerA();
      } else {
        registerA();
        if (scenario.equals("dynamic")) {
          registerDynamic();
        } else {
          if (scenario.equals("pending")) {
            registerC();
          }
          registerB();
        }
      }
      return String.join(",", deliveries);
    }

    private void registerA() {
      Workflow.registerListener((AListener) value -> record("a:" + value));
    }

    private void registerB() {
      Workflow.registerListener((BListener) value -> record("b:" + value));
    }

    private void registerC() {
      Workflow.registerListener((CListener) value -> record("c:" + value));
    }

    private void registerDynamic() {
      Workflow.registerListener(
          (DynamicSignalHandler)
              (name, values) -> record("dynamic:" + name + ":" + values.get(0, String.class)));
    }

    private void record(String value) {
      deliveries.add(value);
      // Each delivery issues a command so replay checks command ordering as well as the result.
      Workflow.sleep(Duration.ofMillis(1));
    }

    @Override
    public void proceed() {
      proceed = true;
    }

    @Override
    public boolean ready() {
      return ready;
    }
  }
}
