package io.temporal.internal.nexus;

import static org.mockito.Mockito.mock;

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationMethodCanceller;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.WorkflowClient;
import io.temporal.common.CancellationToken;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.nexus.TemporalOperationResult;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TemporalInterceptorMiddlewareTest {

  @Before
  public void setUp() {
    CurrentNexusOperationContext.set(
        new InternalNexusOperationContext(
            "namespace", "taskQueue", "endpoint", null, mock(WorkflowClient.class)));
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  @Test
  public void headersRemainAccessibleToInterceptorWithContextFreeHandler()
      throws OperationException {
    OperationContext operationContext =
        OperationContext.newBuilder()
            .setService("service")
            .setOperation("operation")
            .putHeader("test-header", "test-value")
            .build();
    AtomicReference<String> interceptedHeader = new AtomicReference<>();
    WorkerInterceptor interceptor =
        new WorkerInterceptorBase() {
          @Override
          public NexusOperationInboundCallsInterceptor interceptNexusOperation(
              OperationContext context, NexusOperationInboundCallsInterceptor next) {
            interceptedHeader.set(context.getHeaders().get("test-header"));
            return next;
          }
        };
    OperationHandler<Object, Object> handler =
        new TemporalInterceptorMiddleware(new WorkerInterceptor[] {interceptor})
            .intercept(
                operationContext,
                TemporalOperationHandler.create(
                    (client, input) -> TemporalOperationResult.sync(input)));

    Object result =
        handler
            .start(
                operationContext,
                OperationStartDetails.newBuilder().setRequestId("request-id").build(),
                "input")
            .getSyncResult();
    Assert.assertEquals("input", result);
    Assert.assertEquals(
        "worker interceptor should see the request header", "test-value", interceptedHeader.get());
  }

  @Test
  public void methodCancellationReachesNexusOperationInfo() {
    OperationMethodCanceller canceller = new OperationMethodCanceller();
    CancellationToken<CancellationException> token = interceptAndGetCancellationToken(canceller);
    Assert.assertFalse(token.isCancellationRequested());

    canceller.cancel("test reason");

    Assert.assertTrue(token.isCancellationRequested());
    CancellationException exception =
        Assert.assertThrows(CancellationException.class, token::throwIfCancellationRequested);
    Assert.assertEquals("test reason", exception.getMessage());
  }

  @Test
  public void cancellationBeforeInterceptionReachesNexusOperationInfo() {
    OperationMethodCanceller canceller = new OperationMethodCanceller();
    canceller.cancel("early reason");
    CancellationToken<CancellationException> token = interceptAndGetCancellationToken(canceller);

    Assert.assertTrue(token.isCancellationRequested());
    CancellationException exception =
        Assert.assertThrows(CancellationException.class, token::throwIfCancellationRequested);
    Assert.assertEquals("early reason", exception.getMessage());
  }

  private static CancellationToken<CancellationException> interceptAndGetCancellationToken(
      OperationMethodCanceller canceller) {
    OperationContext operationContext =
        OperationContext.newBuilder()
            .setService("service")
            .setOperation("operation")
            .setMethodCanceller(canceller)
            .build();
    TemporalInterceptorMiddleware middleware =
        new TemporalInterceptorMiddleware(new WorkerInterceptor[0]);
    middleware.intercept(
        operationContext, OperationHandler.sync((context, details, input) -> input));
    return Nexus.getOperationContext().getInfo().getCancellationToken();
  }
}
