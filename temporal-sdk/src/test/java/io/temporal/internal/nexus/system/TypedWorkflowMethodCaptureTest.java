package io.temporal.internal.nexus.system;

import static org.junit.Assert.*;

import io.temporal.workflow.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class TypedWorkflowMethodCaptureTest {
  public interface Base {
    @SignalMethod(name = "custom-signal")
    void signal(String value, int number);
  }

  @WorkflowInterface
  public interface Target extends Base {
    @WorkflowMethod(name = "custom-workflow")
    int start(String value);

    @QueryMethod
    String query();

    @UpdateMethod
    String update();
  }

  @Test
  public void usesAnnotatedNamesAndTypedArgumentsWithoutExecutingCommands() {
    TemporalModelConverters.captureWorkflowCall(
        Target.class,
        w -> assertEquals(0, w.start("input")),
        (name, args) -> {
          assertEquals("custom-workflow", name);
          assertArrayEquals(new Object[] {"input"}, args);
        });
    TemporalModelConverters.captureSignalCall(
        Target.class,
        w -> w.signal("value", 42),
        (name, args) -> {
          assertEquals("custom-signal", name);
          assertArrayEquals(new Object[] {"value", 42}, args);
        });
  }

  @Test
  public void rejectsMissingMultipleAndWrongKindOfCalls() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TemporalModelConverters.captureWorkflowCall(
                Target.class, w -> {}, (name, args) -> fail()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TemporalModelConverters.captureWorkflowCall(
                Target.class,
                w -> {
                  w.start("first");
                  w.start("second");
                },
                (name, args) -> fail()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TemporalModelConverters.captureWorkflowCall(
                Target.class, w -> w.signal("signal", 1), (name, args) -> fail()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TemporalModelConverters.captureSignalCall(
                Target.class, w -> w.start("input"), (name, args) -> fail()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TemporalModelConverters.captureSignalCall(
                Target.class, Target::query, (name, args) -> fail()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TemporalModelConverters.captureSignalCall(
                Target.class, Target::update, (name, args) -> fail()));
  }

  @Test
  public void receiversExpireAfterSuccessOrFailureAndCapturesRemainIndependent() {
    AtomicReference<Target> receiver = new AtomicReference<>();
    TemporalModelConverters.captureWorkflowCall(
        Target.class,
        w -> {
          receiver.set(w);
          assertNotNull(w.toString());
          w.start("input");
        },
        (name, args) -> {});
    assertThrows(IllegalStateException.class, () -> receiver.get().start("escaped"));
    RuntimeException failure = new RuntimeException("callback failed");
    assertSame(
        failure,
        assertThrows(
            RuntimeException.class,
            () ->
                TemporalModelConverters.captureWorkflowCall(
                    Target.class,
                    w -> {
                      receiver.set(w);
                      throw failure;
                    },
                    (name, args) -> fail())));
    assertThrows(IllegalStateException.class, () -> receiver.get().signal("escaped", 1));
    TemporalModelConverters.captureWorkflowCall(
        Target.class,
        w -> w.start("next"),
        (name, args) -> assertArrayEquals(new Object[] {"next"}, args));
  }
}
