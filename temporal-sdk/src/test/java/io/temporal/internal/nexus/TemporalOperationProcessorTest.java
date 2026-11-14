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
  public void directReturns_registerAndWrapResults() throws Exception {
    DirectReturn handler = new DirectReturn();
    TemporalOperationProcessor.process(handler);
    TemporalOperationResult<Object> result = invoke(handler, String.class, "direct");
    Assert.assertTrue(result.isSync());
    Assert.assertEquals("direct", result.getSyncResult());
    Assert.assertNull(invoke(handler, String.class, null).getSyncResult());
  }

  @Test
  public void directReturns_preserveCompositeGenericValidation() {
    TemporalOperationProcessor.process(new DirectComposite());
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new DirectCompositeBadOutput()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("output type mismatch"));
  }

  @Test
  public void directReturns_supportPrimitiveAndVoidResults() throws Exception {
    DirectPrimitive primitive = new DirectPrimitive();
    TemporalOperationProcessor.process(primitive);
    Assert.assertEquals(42, invoke(primitive, Integer.class, 42).getSyncResult());

    DirectVoid noOutput = new DirectVoid();
    TemporalOperationProcessor.process(noOutput);
    TemporalOperationResult<Object> result = invoke(noOutput, Void.class, null);
    Assert.assertTrue(result.isSync());
    Assert.assertNull(result.getSyncResult());
    Assert.assertTrue(noOutput.invoked);
  }

  @Test
  public void wrappedReturns_preserveSyncAndAsyncResults() throws Exception {
    for (TemporalOperationResult<String> expected :
        java.util.Arrays.asList(
            TemporalOperationResult.sync("wrapped"),
            TemporalOperationResult.<String>async("token"))) {
      WrappedReturn handler = new WrappedReturn(expected);
      TemporalOperationProcessor.process(handler);
      Assert.assertSame(expected, invoke(handler, String.class, "input"));
    }
  }

  @Test
  public void directReturns_propagateOperationExceptionUnwrapped() throws Exception {
    DirectFailure handler = new DirectFailure();
    TemporalOperationProcessor.process(handler);
    io.nexusrpc.OperationException thrown =
        Assert.assertThrows(
            io.nexusrpc.OperationException.class, () -> invoke(handler, String.class, "input"));
    Assert.assertSame(handler.failure, thrown);
  }

  @Test
  public void directReturns_propagateRuntimeExceptionUnwrapped() throws Exception {
    DirectFailure handler = new DirectFailure();
    TemporalOperationProcessor.process(handler);
    IllegalStateException thrown =
        Assert.assertThrows(
            IllegalStateException.class, () -> invoke(handler, String.class, "runtime"));
    Assert.assertSame(handler.runtimeFailure, thrown);
  }

  @Test
  public void rejects_compositeGenericInputMismatch() {
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new CompositeBadInput()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("input type mismatch"));
  }

  @Test
  public void rejects_compositeGenericOutputMismatch() {
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new CompositeBadOutput()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("output type mismatch"));
  }

  @Test
  public void rejects_rawReturnType() {
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new RawReturnType()));
    Assert.assertTrue(
        e.getMessage(), e.getMessage().contains("must use parameterized TemporalOperationResult"));
  }

  @Test
  public void rejects_nonPublicMethod() {
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new NonPublicMethod()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("must be public"));
  }

  @Test
  public void rejects_staticMethod() {
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new StaticMethod()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("must not be static"));
  }

  @Test
  public void invokeStartHandler_readsInstanceField() throws Exception {
    // The method handle must be bound to the @ServiceImpl instance so the user's method can
    // read constructor-injected dependencies (workflow client, config, etc.) off `this`.
    StatefulHandler instance = new StatefulHandler("injected");
    java.lang.reflect.Method m =
        StatefulHandler.class.getMethod("op", TemporalNexusClient.class, String.class);
    java.lang.invoke.MethodHandle handle =
        java.lang.invoke.MethodHandles.lookup().unreflect(m).bindTo(instance);
    TemporalOperationResult<Object> result =
        TemporalOperationProcessor.invokeStartHandler(handle, null, "input");
    Assert.assertEquals("injected:input", result.getSyncResult());
  }

  @Test
  public void invokeStartHandler_propagatesRuntimeExceptionUnwrapped() throws Exception {
    // A RuntimeException thrown from a user @TemporalOperation method must arrive at the
    // caller without an InvocationTargetException or RuntimeException wrapper inserted by the
    // dispatch path.
    ThrowingHandler instance = new ThrowingHandler();
    java.lang.reflect.Method m =
        ThrowingHandler.class.getMethod("op", TemporalNexusClient.class, String.class);
    java.lang.invoke.MethodHandle handle =
        java.lang.invoke.MethodHandles.lookup().unreflect(m).bindTo(instance);
    IllegalStateException thrown =
        Assert.assertThrows(
            IllegalStateException.class,
            () -> TemporalOperationProcessor.invokeStartHandler(handle, null, "in"));
    Assert.assertEquals("user-thrown", thrown.getMessage());
    // First user frame is at the top — no reflective wrapper class in between.
    Assert.assertEquals(ThrowingHandler.class.getName(), thrown.getStackTrace()[0].getClassName());
  }

  @Test
  public void rejects_badReturnType() {
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new BadReturnType()));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains("output type mismatch"));
  }

  @Test
  public void rejects_badArity() {
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new BadArity()));
    Assert.assertTrue(
        e.getMessage(), e.getMessage().contains("must accept (TemporalNexusClient, I)"));
  }

  @Test
  public void rejects_checkedExceptionInThrows() {
    IllegalArgumentException e =
        assertInvalid(() -> TemporalOperationProcessor.process(new DeclaresCheckedException()));
    Assert.assertTrue(
        e.getMessage(),
        e.getMessage()
            .contains("may only declare OperationException or HandlerException as checked"));
    Assert.assertTrue(e.getMessage(), e.getMessage().contains(java.io.IOException.class.getName()));
  }

  @Test
  public void allows_runtimeExceptionInThrows() {
    // Unchecked exceptions in the throws clause are fine — they don't constrain callers.
    TemporalOperationProcessor.process(new DeclaresRuntimeException());
  }

  @Test
  public void allows_nexusrpcCheckedExceptionsInThrows() {
    // OperationException and HandlerException are the nexusrpc-sanctioned checked exceptions on
    // OperationHandler.start, so we let them through.
    TemporalOperationProcessor.process(new DeclaresNexusrpcCheckedExceptions());
  }

  @Test
  public void operationImpl_takesPrecedenceOverTemporalOperation() {
    Assert.assertEquals(
        1, TemporalOperationProcessor.process(new DualAnnotated()).getOperationHandlers().size());
  }

  private static IllegalArgumentException assertInvalid(Runnable action) {
    RuntimeException wrapper = Assert.assertThrows(RuntimeException.class, action::run);
    Assert.assertTrue(wrapper.getCause() instanceof IllegalArgumentException);
    return (IllegalArgumentException) wrapper.getCause();
  }

  private static TemporalOperationResult<Object> invoke(
      Object instance, Class<?> inputType, Object input) throws Exception {
    java.lang.reflect.Method method =
        instance.getClass().getMethod("op", TemporalNexusClient.class, inputType);
    return TemporalOperationProcessor.invokeStartHandler(
        java.lang.invoke.MethodHandles.lookup().unreflect(method).bindTo(instance), null, input);
  }

  // ----- Fixtures -----

  @ServiceImpl(service = SingleOpService.class)
  public static class ValidSugar {
    @TemporalOperation
    public TemporalOperationResult<String> op(TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class BadReturnType {
    @TemporalOperation
    public Integer op(TemporalNexusClient client, String input) {
      return 42;
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class DirectReturn {
    @TemporalOperation
    public String op(TemporalNexusClient client, String input) {
      return input;
    }
  }

  @ServiceImpl(service = CompositeGenericService.class)
  public static class DirectComposite {
    @TemporalOperation
    public java.util.List<String> compose(
        TemporalNexusClient client, java.util.Map<String, Integer> input) {
      return java.util.Collections.emptyList();
    }
  }

  @ServiceImpl(service = CompositeGenericService.class)
  public static class DirectCompositeBadOutput {
    @TemporalOperation
    public java.util.List<Integer> compose(
        TemporalNexusClient client, java.util.Map<String, Integer> input) {
      return java.util.Collections.emptyList();
    }
  }

  @Service
  public interface PrimitiveService {
    @Operation
    Integer op(Integer input);
  }

  @ServiceImpl(service = PrimitiveService.class)
  public static class DirectPrimitive {
    @TemporalOperation
    public int op(TemporalNexusClient client, Integer input) {
      return input;
    }
  }

  @ServiceImpl(service = VoidIoService.class)
  public static class DirectVoid {
    boolean invoked;

    @TemporalOperation
    public void op(TemporalNexusClient client, Void input) {
      invoked = true;
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class WrappedReturn {
    private final TemporalOperationResult<String> result;

    WrappedReturn(TemporalOperationResult<String> result) {
      this.result = result;
    }

    @TemporalOperation
    public TemporalOperationResult<String> op(TemporalNexusClient client, String input) {
      return result;
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class DirectFailure {
    final io.nexusrpc.OperationException failure =
        io.nexusrpc.OperationException.failed(new IllegalArgumentException("direct failure"));
    final IllegalStateException runtimeFailure =
        new IllegalStateException("direct runtime failure");

    @TemporalOperation
    public String op(TemporalNexusClient client, String input)
        throws io.nexusrpc.OperationException {
      if ("runtime".equals(input)) {
        throw runtimeFailure;
      }
      throw failure;
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class BadArity {
    @TemporalOperation
    public TemporalOperationResult<String> op(String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = CompositeGenericService.class)
  public static class CompositeOk {
    @TemporalOperation
    public TemporalOperationResult<java.util.List<String>> compose(
        TemporalNexusClient client, java.util.Map<String, Integer> input) {
      return TemporalOperationResult.sync(java.util.Collections.emptyList());
    }
  }

  @ServiceImpl(service = CompositeGenericService.class)
  public static class CompositeBadInput {
    // Map value type is String instead of Integer.
    @TemporalOperation
    public TemporalOperationResult<java.util.List<String>> compose(
        TemporalNexusClient client, java.util.Map<String, String> input) {
      return TemporalOperationResult.sync(java.util.Collections.emptyList());
    }
  }

  @ServiceImpl(service = CompositeGenericService.class)
  public static class CompositeBadOutput {
    // Result list element type is Integer instead of String.
    @TemporalOperation
    public TemporalOperationResult<java.util.List<Integer>> compose(
        TemporalNexusClient client, java.util.Map<String, Integer> input) {
      return TemporalOperationResult.sync(java.util.Collections.emptyList());
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @ServiceImpl(service = SingleOpService.class)
  public static class RawReturnType {
    @TemporalOperation
    public TemporalOperationResult op(TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class NonPublicMethod {
    @TemporalOperation
    TemporalOperationResult<String> op(TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class StaticMethod {
    @TemporalOperation
    public static TemporalOperationResult<String> op(TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class StatefulHandler {
    private final String prefix;

    public StatefulHandler(String prefix) {
      this.prefix = prefix;
    }

    @TemporalOperation
    public TemporalOperationResult<String> op(TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync(prefix + ":" + input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class ThrowingHandler {
    @TemporalOperation
    public TemporalOperationResult<String> op(TemporalNexusClient client, String input) {
      throw new IllegalStateException("user-thrown");
    }
  }

  @ServiceImpl(service = VoidIoService.class)
  public static class VoidIo {
    @TemporalOperation
    public TemporalOperationResult<Void> op(TemporalNexusClient client, Void input) {
      return TemporalOperationResult.sync(null);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class DeclaresCheckedException {
    @TemporalOperation
    public TemporalOperationResult<String> op(TemporalNexusClient client, String input)
        throws java.io.IOException {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class DeclaresRuntimeException {
    @TemporalOperation
    public TemporalOperationResult<String> op(TemporalNexusClient client, String input)
        throws IllegalStateException {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class DeclaresNexusrpcCheckedExceptions {
    @TemporalOperation
    public TemporalOperationResult<String> op(TemporalNexusClient client, String input)
        throws io.nexusrpc.OperationException, io.nexusrpc.handler.HandlerException {
      return TemporalOperationResult.sync(input);
    }
  }

  @ServiceImpl(service = SingleOpService.class)
  public static class DualAnnotated {
    @TemporalOperation
    @OperationImpl
    public OperationHandler<String, String> op() {
      return new TemporalOperationHandler<String, String>(
          (client, input) -> TemporalOperationResult.sync(input)) {};
    }
  }
}
