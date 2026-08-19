package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.PayloadValidationException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.*;

/**
 * Verifies how a caller workflow sees failures a data converter raises while deserializing Nexus
 * operation input. A {@link HandlerException} keeps the error type and retry behavior the converter
 * chose, and an {@link ApplicationFailure} is wrapped by {@link
 * io.temporal.internal.nexus.NexusTaskHandlerImpl} the same way one thrown from an operation
 * handler is. Neither is rewritten to BAD_REQUEST, which is what happens to every other failure,
 * with one exception: a non-retryable {@code PayloadValidationError} is the converter's way of
 * saying the input itself was invalid, so it is reported as a non-retryable BAD_REQUEST.
 */
public class OperationInputDeserializationErrorPropagationTest {
  private static final String HANDLER_EXCEPTION = "handler-exception";
  private static final String NON_RETRYABLE_APPLICATION_FAILURE =
      "non-retryable-application-failure";
  private static final String RETRYABLE_APPLICATION_FAILURE = "retryable-application-failure";
  private static final String NON_RETRYABLE_PAYLOAD_VALIDATION_ERROR =
      "non-retryable-payload-validation-error";
  private static final String RETRYABLE_PAYLOAD_VALIDATION_ERROR =
      "retryable-payload-validation-error";
  private static final String CODEC_FAILURE = "codec-failure";

  private static final AtomicInteger deserializeAttempts = new AtomicInteger();
  private static final AtomicInteger operationInvocations = new AtomicInteger();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new PoisonInputServiceImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(new PoisonInputDataConverter())
                  .build())
          .build();

  // Check if we're forcing old format via system property
  private static boolean isUsingNewFormat() {
    return !("true".equalsIgnoreCase(System.getProperty("temporal.nexus.forceOldFailureFormat")));
  }

  @Before
  public void setUp() {
    deserializeAttempts.set(0);
    operationInvocations.set(0);
  }

  private HandlerException executeAndGetHandlerException(String mode) {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(mode));
    Assert.assertTrue(workflowException.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) workflowException.getCause();
    Assert.assertTrue(
        "expected a HandlerException, got " + nexusFailure.getCause(),
        nexusFailure.getCause() instanceof HandlerException);
    return (HandlerException) nexusFailure.getCause();
  }

  @Test
  public void handlerExceptionKeepsItsErrorType() {
    HandlerException handlerFailure = executeAndGetHandlerException(HANDLER_EXCEPTION);

    // NOT_FOUND rather than the BAD_REQUEST every other deserialization failure is reported as,
    // so this also proves the converter's choice was not overwritten.
    Assert.assertEquals(HandlerException.ErrorType.NOT_FOUND, handlerFailure.getErrorType());
    Assert.assertFalse(handlerFailure.isRetryable());

    Assert.assertEquals(1, deserializeAttempts.get());
    Assert.assertEquals(0, operationInvocations.get());
  }

  @Test
  public void nonRetryableApplicationFailureBecomesNonRetryableInternal() {
    HandlerException handlerFailure =
        executeAndGetHandlerException(NON_RETRYABLE_APPLICATION_FAILURE);

    Assert.assertEquals(HandlerException.ErrorType.INTERNAL, handlerFailure.getErrorType());
    Assert.assertEquals(
        HandlerException.RetryBehavior.NON_RETRYABLE, handlerFailure.getRetryBehavior());
    Assert.assertFalse(handlerFailure.isRetryable());
    if (isUsingNewFormat()) {
      Assert.assertEquals(
          "Handler failed with non-retryable application error", handlerFailure.getMessage());
    }
    Throwable cause = handlerFailure.getCause();
    Assert.assertNotNull(cause);
    Assert.assertTrue(cause.getMessage().contains("intentional failure"));

    Assert.assertEquals(1, deserializeAttempts.get());
    Assert.assertEquals(0, operationInvocations.get());
  }

  @Test(timeout = 30000)
  public void retryableApplicationFailureIsRetried() {
    assertRetriedUntilTimeout(RETRYABLE_APPLICATION_FAILURE);
  }

  @Test
  public void nonRetryablePayloadValidationErrorBecomesNonRetryableBadRequest() {
    HandlerException handlerFailure =
        executeAndGetHandlerException(NON_RETRYABLE_PAYLOAD_VALIDATION_ERROR);

    // BAD_REQUEST rather than the INTERNAL every other non-retryable ApplicationFailure gets.
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, handlerFailure.getErrorType());
    Assert.assertFalse(handlerFailure.isRetryable());
    if (isUsingNewFormat()) {
      Assert.assertEquals("invalid operation input", handlerFailure.getMessage());
    }
    // The wrapper message does not carry the converter's own message, so it has to survive on the
    // cause for the caller to see why the input was rejected.
    Throwable cause = handlerFailure.getCause();
    Assert.assertNotNull(cause);
    Assert.assertTrue(
        "expected an ApplicationFailure cause, got " + cause, cause instanceof ApplicationFailure);
    Assert.assertEquals("PayloadValidationError", ((ApplicationFailure) cause).getType());
    Assert.assertTrue(
        "expected the converter's message on the cause, got " + cause.getMessage(),
        cause.getMessage().contains("Payload validation failed"));

    Assert.assertEquals(1, deserializeAttempts.get());
    Assert.assertEquals(0, operationInvocations.get());
  }

  /**
   * The PayloadValidationError type only opts into BAD_REQUEST when the failure is non-retryable,
   * so a retryable one keeps being retried.
   */
  @Test(timeout = 30000)
  public void retryablePayloadValidationErrorIsRetried() {
    assertRetriedUntilTimeout(RETRYABLE_PAYLOAD_VALIDATION_ERROR);
  }

  /**
   * A payload codec outage is not the caller's fault and may resolve on its own, so it must not be
   * reported as a non-retryable BAD_REQUEST the way undeserializable input is.
   */
  @Test(timeout = 30000)
  public void codecFailureIsRetried() {
    assertRetriedUntilTimeout(CODEC_FAILURE);
  }

  private void assertRetriedUntilTimeout(String mode) {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(mode));

    // Retried until the operation's schedule-to-close timeout, so the caller sees a timeout
    // rather than the handler error itself.
    Assert.assertTrue(workflowException.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) workflowException.getCause();
    Assert.assertTrue(
        "expected a TimeoutFailure, got " + nexusFailure.getCause(),
        nexusFailure.getCause() instanceof TimeoutFailure);

    Assert.assertTrue(
        "expected more than one attempt, got " + deserializeAttempts.get(),
        deserializeAttempts.get() > 1);
    Assert.assertEquals(0, operationInvocations.get());
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String mode) {
      PoisonInputService service =
          Workflow.newNexusServiceStub(
              PoisonInputService.class,
              NexusServiceOptions.newBuilder()
                  .setOperationOptions(
                      NexusOperationOptions.newBuilder()
                          .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                          .build())
                  .build());
      return service.operation(new FailureMode(mode));
    }
  }

  /** Operation input type the data converter refuses to deserialize. */
  public static class FailureMode {
    public String mode;

    public FailureMode() {}

    FailureMode(String mode) {
      this.mode = mode;
    }
  }

  @Service
  public interface PoisonInputService {
    @Operation
    String operation(FailureMode input);
  }

  @ServiceImpl(service = PoisonInputService.class)
  public static class PoisonInputServiceImpl {
    @OperationImpl
    public OperationHandler<FailureMode, String> operation() {
      return OperationHandler.sync(
          (ctx, details, input) -> {
            operationInvocations.incrementAndGet();
            return input.mode;
          });
    }
  }

  /**
   * Delegates everything to the standard converter except deserializing a {@link FailureMode},
   * which fails with the exception that value names.
   */
  private static class PoisonInputDataConverter implements DataConverter {
    private final DataConverter delegate = DefaultDataConverter.STANDARD_INSTANCE;

    @Override
    public <T> Optional<Payload> toPayload(T value) {
      return delegate.toPayload(value);
    }

    @Override
    public Optional<Payloads> toPayloads(Object... values) {
      return delegate.toPayloads(values);
    }

    @Override
    public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
      if (valueClass == FailureMode.class) {
        deserializeAttempts.incrementAndGet();
        throw failureFor(delegate.fromPayload(payload, FailureMode.class, FailureMode.class).mode);
      }
      return delegate.fromPayload(payload, valueClass, valueType);
    }

    @Override
    public <T> T fromPayloads(
        int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType) {
      return delegate.fromPayloads(index, content, valueType, valueGenericType);
    }

    @Nonnull
    @Override
    public RuntimeException failureToException(@Nonnull Failure failure) {
      return delegate.failureToException(failure);
    }

    @Nonnull
    @Override
    public Failure exceptionToFailure(@Nonnull Throwable throwable) {
      return delegate.exceptionToFailure(throwable);
    }

    private static RuntimeException failureFor(String mode) {
      switch (mode) {
        case HANDLER_EXCEPTION:
          return new HandlerException(
              HandlerException.ErrorType.NOT_FOUND, new RuntimeException("intentional failure"));
        case NON_RETRYABLE_APPLICATION_FAILURE:
          return ApplicationFailure.newNonRetryableFailure("intentional failure", "TestFailure");
        case RETRYABLE_APPLICATION_FAILURE:
          return ApplicationFailure.newFailure("intentional failure", "TestFailure");
        case NON_RETRYABLE_PAYLOAD_VALIDATION_ERROR:
          return PayloadValidationException.create(
              Collections.singletonList("intentional validation failure"));
        case RETRYABLE_PAYLOAD_VALIDATION_ERROR:
          return ApplicationFailure.newFailure("intentional failure", "PayloadValidationError");
        case CODEC_FAILURE:
          return new PayloadCodecException("intentional failure");
        default:
          throw new IllegalStateException("unknown failure mode: " + mode);
      }
    }
  }
}
