package io.temporal.workflow.shared;

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationCancelDetails;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.OperationStartDetails;
import io.nexusrpc.handler.OperationStartResult;
import io.nexusrpc.handler.ServiceImpl;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared {@link TestNexusServices.TestNexusService1} implementation used by the standalone Nexus
 * client tests. Behaviour is driven entirely by the input string:
 *
 * <ul>
 *   <li>An input starting with {@link #FAIL_PREFIX} causes {@code start} to throw an {@link
 *       OperationException#failed} so callers see a non-retryable handler failure.
 *   <li>An input starting with {@link #ASYNC_PREFIX} causes {@code start} to return an
 *       async-started result with a synthetic operation token; the operation stays in {@code
 *       RUNNING} until something terminal (cancel that takes effect, terminate, schedule-to-close)
 *       transitions it.
 *   <li>Any other input is echoed back as {@code "echo:" + input}.
 * </ul>
 *
 * <p>Cancel callbacks increment {@link #cancelInvocations} so tests can assert cancel-RPC delivery
 * end-to-end. The counter is process-wide; tests that care should capture a baseline before the
 * cancel and assert the post-cancel value is strictly greater.
 */
@ServiceImpl(service = TestNexusServices.TestNexusService1.class)
public class EchoNexusServiceImpl {

  /** Inputs starting with this prefix make {@code start} throw, exercising the failure path. */
  public static final String FAIL_PREFIX = "FAIL:";

  /**
   * Inputs starting with this prefix make {@code start} return an async-started result without ever
   * completing the operation. Used by cancel/terminate tests so the operation stays in {@code
   * RUNNING} long enough for the lifecycle RPC to be observed.
   */
  public static final String ASYNC_PREFIX = "ASYNC:";

  /**
   * Incremented every time the worker invokes the handler's {@code cancel(...)} callback. Tests
   * that want to assert end-to-end cancel-RPC delivery (client → server → worker) read the value
   * before the cancel, issue the cancel, then poll until this counter exceeds the baseline.
   */
  public static final AtomicInteger cancelInvocations = new AtomicInteger();

  @OperationImpl
  public OperationHandler<String, String> operation() {
    return new OperationHandler<String, String>() {
      @Override
      public OperationStartResult<String> start(
          OperationContext context, OperationStartDetails details, String input)
          throws OperationException {
        if (input != null && input.startsWith(FAIL_PREFIX)) {
          throw OperationException.failed("intentional failure: " + input);
        }
        if (input != null && input.startsWith(ASYNC_PREFIX)) {
          return OperationStartResult.async("token-" + UUID.randomUUID());
        }
        return OperationStartResult.sync("echo:" + (input == null ? "<null>" : input));
      }

      @Override
      public void cancel(OperationContext context, OperationCancelDetails details) {
        cancelInvocations.incrementAndGet();
      }
    };
  }
}
