package io.temporal.internal.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.nexus.TemporalNexusClient;
import io.temporal.nexus.TemporalOperation;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.nexus.TemporalOperationResult;
import io.temporal.nexus.TemporalOperationStartContext;
import org.junit.Assert;
import org.junit.Test;

public class TemporalOperationProcessorTest {

  @Service
  public interface SingleOpService {
    @Operation
    String op(String input);
  }

  @Service
  public interface CompositeGenericService {
    @Operation
    java.util.List<String> compose(java.util.Map<String, Integer> input);
  }

  @Service
  public interface VoidIoService {
    @Operation
    Void op();
  }

  @Test
  public void happyPath_registersTemporalOperation() {
    TemporalOperationProcessor.process(new ValidSugar());
    // No exception → registration succeeded. End-to-end behavior is covered in
    // TemporalOperationAnnotationTest.
  }

  @Test
  public void happyPath_compositeGenerics() {
    // Validation must traverse parameterized types (List<String>, Map<String, Integer>).
    TemporalOperationProcessor.process(new CompositeOk());
  }

  @Test
  public void happyPath_voidInputAndOutput() {
    // A no-input @Operation (Void op()) must register: declared Void param matches Void input,
    // and a Void result type matches the operation's Void output.
    TemporalOperationProcessor.process(new VoidIo());
  }

  @Test
  public void rejects_compositeGenericInputMismatch() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> TemporalOperationProcessor.process(new CompositeBadInput()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("input type mismatch"));
  }

  @Test
  public void rejects_compositeGenericOutputMismatch() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> TemporalOperationProcessor.process(new CompositeBadOutput()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("output type mismatch"));
  }

  @Test
  public void rejects_rawReturnType() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> TemporalOperationProcessor.process(new RawReturnType()));
    Assert.assertTrue(
        e.getMessage(), e.getMessage().contains("must use parameterized TemporalOperationResult"));
  }

  @Test
  public void rejects_nonPublicMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> TemporalOperationProcessor.process(new NonPublicMethod()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("must be public"));
  }

  @Test
  public void rejects_staticMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> TemporalOperationProcessor.process(new StaticMethod()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("must not be static"));
  }

  @Test
  public void invokeStartHandler_propagatesRuntimeExceptionUnwrapped() throws Exception {
    // A RuntimeException thrown from a user @TemporalOperation method must arrive at the
    // caller without an InvocationTargetException or RuntimeException wrapper inserted by the
    // dispatch path.
    ThrowingHandler instance = new ThrowingHandler();
    java.lang.reflect.Method m =
        ThrowingHandler.class.getMethod(
            "op", TemporalOperationStartContext.class, TemporalNexusClient.class, String.class);
    java.lang.invoke.MethodHandle handle =
        java.lang.invoke.MethodHandles.lookup().unreflect(m).bindTo(instance);
    IllegalStateException thrown =
        Assert.assertThrows(
            IllegalStateException.class,
            () -> TemporalOperationProcessor.invokeStartHandler(handle, null, null, "in"));
    Assert.assertEquals("user-thrown", thrown.getMessage());
    // First user frame is at the top — no reflective wrapper class in between.
    Assert.assertEquals(ThrowingHandler.class.getName(), thrown.getStackTrace()[0].getClassName());
  }

  @Test
  public void rejects_badReturnType() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> TemporalOperationProcessor.process(new BadReturnType()));
    Assert.assertTrue(
        e.getMessage(), e.getMessage().contains("must return TemporalOperationResult"));
  }

  @Test
  public void rejects_badArity() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> TemporalOperationProcessor.process(new BadArity()));
    Assert.assertTrue(
        e.getMessage(),
        e.getMessage()
            .contains("must accept (TemporalOperationStartContext, TemporalNexusClient, I)"));
  }

  @Test
  public void rejects_dualAnnotation() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () -> TemporalOperationProcessor.process(new DualAnnotated()));
    Assert.assertTrue(
        e.getMessage(),
        e.getMessage().contains("@TemporalOperation and @OperationImpl cannot be combined"));
  }

  // ----- Fixtures -----

  @ServiceImpl(service = SingleOpService.class)
  public static class ValidSugar {
    @TemporalOperation
    public TemporalOperationResult<String> op(
        TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class BadReturnType {
    @TemporalOperation
    public String op(TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      return input;
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class BadArity {
    @TemporalOperation
    public TemporalOperationResult<String> op(TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = CompositeGenericService.class)
  public static class CompositeOk {
    @TemporalOperation
    public TemporalOperationResult<java.util.List<String>> compose(
        TemporalOperationStartContext ctx,
        TemporalNexusClient client,
        java.util.Map<String, Integer> input) {
      return TemporalOperationResult.sync(java.util.Collections.emptyList());
    }
  }

  @ServiceImpl(service = CompositeGenericService.class)
  public static class CompositeBadInput {
    // Map value type is String instead of Integer.
    @TemporalOperation
    public TemporalOperationResult<java.util.List<String>> compose(
        TemporalOperationStartContext ctx,
        TemporalNexusClient client,
        java.util.Map<String, String> input) {
      return TemporalOperationResult.sync(java.util.Collections.emptyList());
    }
  }

  @ServiceImpl(service = CompositeGenericService.class)
  public static class CompositeBadOutput {
    // Result list element type is Integer instead of String.
    @TemporalOperation
    public TemporalOperationResult<java.util.List<Integer>> compose(
        TemporalOperationStartContext ctx,
        TemporalNexusClient client,
        java.util.Map<String, Integer> input) {
      return TemporalOperationResult.sync(java.util.Collections.emptyList());
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @ServiceImpl(service = SingleOpService.class)
  public static class RawReturnType {
    @TemporalOperation
    public TemporalOperationResult op(
        TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class NonPublicMethod {
    @TemporalOperation
    TemporalOperationResult<String> op(
        TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class StaticMethod {
    @TemporalOperation
    public static TemporalOperationResult<String> op(
        TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class ThrowingHandler {
    @TemporalOperation
    public TemporalOperationResult<String> op(
        TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      throw new IllegalStateException("user-thrown");
    }
  }

  @ServiceImpl(service = VoidIoService.class)
  public static class VoidIo {
    @TemporalOperation
    public TemporalOperationResult<Void> op(
        TemporalOperationStartContext ctx, TemporalNexusClient client, Void input) {
      return TemporalOperationResult.sync(null);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class DualAnnotated {
    @TemporalOperation
    @OperationImpl
    public OperationHandler<String, String> op() {
      return new TemporalOperationHandler<String, String>(
          (ctx, client, input) -> TemporalOperationResult.sync(input)) {};
    }
  }
}
