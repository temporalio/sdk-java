package io.temporal.internal.nexus.system;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.metadata.WorkflowMethodType;
import io.temporal.internal.sync.ExternalWorkflowInvocationHandler;
import io.temporal.internal.sync.ExternalWorkflowInvocationHandler.ProbeResult;
import io.temporal.internal.sync.StubMarker;
import io.temporal.workflow.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;

public class TypedWorkflowMethodCaptureTest {
  public interface Base {
    @SignalMethod(name = "custom-signal")
    void signal(String value, int number);
  }

  @WorkflowInterface
  public interface Target extends Base {
    @WorkflowMethod(name = "custom-workflow")
    @MethodRetry(maximumAttempts = 3)
    @CronSchedule("0 * * * *")
    int start(String value);

    @QueryMethod
    String query();

    @UpdateMethod
    String update();
  }

  @WorkflowInterface
  public interface GenericTarget {
    @WorkflowMethod
    List<String> start();
  }

  private final WorkflowOutboundCallsInterceptor outbound =
      mock(WorkflowOutboundCallsInterceptor.class);
  private final RuntimeException normalSignal = new IllegalStateException("normal signal path");

  private <T> T stub(Class<T> type, String id) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type, StubMarker.class},
            new ExternalWorkflowInvocationHandler(
                type,
                WorkflowExecution.newBuilder().setWorkflowId(id).build(),
                outbound,
                ignored -> {
                  throw normalSignal;
                })));
  }

  @Test
  public void probesNamesArgumentsOptionsAndReturnTypesWithoutCommands() {
    Target target = stub(Target.class, "target");
    Functions.Func1<String, Integer> method = target::start;
    ProbeResult call =
        ExternalWorkflowInvocationHandler.probe(
            method,
            () -> assertEquals(0, (int) method.apply("input")),
            WorkflowMethodType.WORKFLOW);
    assertEquals("custom-workflow", call.getName());
    assertArrayEquals(new Object[] {"input"}, call.getArguments());
    assertEquals(int.class, call.getResultClass());
    assertEquals(int.class, call.getResultType());
    assertEquals("target", call.getOptions().getWorkflowId());
    assertEquals(3, call.getOptions().getRetryOptions().getMaximumAttempts());
    assertEquals("0 * * * *", call.getOptions().getCronSchedule());
    assertSame(ExternalWorkflowStub.fromTyped(target), call.getStub());
    call.getArguments()[0] = "changed";
    assertEquals("input", call.getArguments()[0]);

    Functions.Proc2<String, Integer> signal = target::signal;
    ProbeResult signalCall =
        ExternalWorkflowInvocationHandler.probe(
            signal, () -> signal.apply("value", 42), WorkflowMethodType.SIGNAL);
    assertEquals("custom-signal", signalCall.getName());
    assertArrayEquals(new Object[] {"value", 42}, signalCall.getArguments());
    GenericTarget generic = stub(GenericTarget.class, "generic");
    Functions.Func<List<String>> start = generic::start;
    ProbeResult genericCall =
        ExternalWorkflowInvocationHandler.probe(
            start, () -> start.apply(), WorkflowMethodType.WORKFLOW);
    assertEquals("java.util.List<java.lang.String>", genericCall.getResultType().getTypeName());
    verifyNoInteractions(outbound);
    assertThrows(IllegalStateException.class, () -> target.start("outside probe"));
    assertSame(
        normalSignal, assertThrows(RuntimeException.class, () -> target.signal("outside", 1)));
  }

  @Test
  public void rejectsMissingRepeatedWrongKindAndNonBoundInvocations() {
    Target target = stub(Target.class, "target");
    Functions.Func1<String, Integer> start = target::start;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExternalWorkflowInvocationHandler.probe(start, () -> {}, WorkflowMethodType.WORKFLOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExternalWorkflowInvocationHandler.probe(
                start,
                () -> {
                  start.apply("first");
                  start.apply("second");
                },
                WorkflowMethodType.WORKFLOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExternalWorkflowInvocationHandler.probe(
                start, () -> start.apply("input"), WorkflowMethodType.SIGNAL));
    Functions.Proc2<String, Integer> signal = target::signal;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExternalWorkflowInvocationHandler.probe(
                signal, () -> signal.apply("value", 1), WorkflowMethodType.WORKFLOW));
    Functions.Func<String> query = target::query;
    Functions.Func<String> update = target::update;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExternalWorkflowInvocationHandler.probe(
                query, () -> query.apply(), WorkflowMethodType.SIGNAL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExternalWorkflowInvocationHandler.probe(
                update, () -> update.apply(), WorkflowMethodType.SIGNAL));
    Functions.Proc lambda = () -> target.start("input");
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalWorkflowInvocationHandler.probe(lambda, lambda, WorkflowMethodType.WORKFLOW));
    verifyNoInteractions(outbound);
  }

  @Test
  public void restoresProbeStateAfterFailureAndRejectsNesting() {
    Target target = stub(Target.class, "target");
    Functions.Func1<String, Integer> method = target::start;
    RuntimeException failure = new RuntimeException("failed");
    assertSame(
        failure,
        assertThrows(
            RuntimeException.class,
            () ->
                ExternalWorkflowInvocationHandler.probe(
                    method,
                    () -> {
                      throw failure;
                    },
                    WorkflowMethodType.WORKFLOW)));
    ProbeResult result =
        ExternalWorkflowInvocationHandler.probe(
            method,
            () -> {
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      ExternalWorkflowInvocationHandler.probe(
                          method, () -> method.apply("nested"), WorkflowMethodType.WORKFLOW));
              method.apply("outer");
            },
            WorkflowMethodType.WORKFLOW);
    assertArrayEquals(new Object[] {"outer"}, result.getArguments());
    assertThrows(IllegalStateException.class, () -> target.start("outside probe"));
    verifyNoInteractions(outbound);
  }

  @Test
  public void isolatesProbeResultsAcrossThreads() throws Exception {
    Target first = stub(Target.class, "first");
    Target second = stub(Target.class, "second");
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> a = executor.submit(() -> parallelProbe(first::start, "a", barrier));
      Future<String> b = executor.submit(() -> parallelProbe(second::start, "b", barrier));
      assertEquals("first:a", a.get(10, TimeUnit.SECONDS));
      assertEquals("second:b", b.get(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
    verifyNoInteractions(outbound);
  }

  private String parallelProbe(
      Functions.Func1<String, Integer> method, String value, CyclicBarrier barrier) {
    ProbeResult result =
        ExternalWorkflowInvocationHandler.probe(
            method,
            () -> {
              try {
                barrier.await(5, TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              method.apply(value);
            },
            WorkflowMethodType.WORKFLOW);
    return result.getOptions().getWorkflowId() + ":" + result.getArguments()[0];
  }
}
