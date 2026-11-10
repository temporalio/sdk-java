package io.temporal.internal.nexus;

import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationMethodCanceller;
import io.temporal.common.CancellationToken;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.nexus.Nexus;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class TemporalInterceptorMiddlewareTest {

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  @Test
  public void methodCancellationReachesNexusOperationInfo() {
    OperationMethodCanceller canceller = new OperationMethodCanceller();
    OperationContext operationContext =
        OperationContext.newBuilder()
            .setService("service")
            .setOperation("operation")
            .setMethodCanceller(canceller)
            .build();
    installOperationContext(operationContext);

    CancellationToken<CancellationException> token = Nexus.getInfo().getCancellationToken();
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
    OperationContext operationContext =
        OperationContext.newBuilder()
            .setService("service")
            .setOperation("operation")
            .setMethodCanceller(canceller)
            .build();
    installOperationContext(operationContext);

    CancellationToken<CancellationException> token = Nexus.getInfo().getCancellationToken();

    Assert.assertTrue(token.isCancellationRequested());
    CancellationException exception =
        Assert.assertThrows(CancellationException.class, token::throwIfCancellationRequested);
    Assert.assertEquals("early reason", exception.getMessage());
  }

  @Test
  public void deadlineReachesNexusOperationInfo() {
    Instant deadline = Instant.parse("2030-01-02T03:04:05Z");
    OperationContext operationContext =
        OperationContext.newBuilder()
            .setService("service")
            .setOperation("operation")
            .setDeadline(deadline)
            .build();
    installOperationContext(operationContext);

    Assert.assertEquals(deadline, Nexus.getInfo().getDeadline());
  }

  private static void installOperationContext(OperationContext operationContext) {
    InternalNexusOperationContext temporalContext =
        new InternalNexusOperationContext("namespace", "taskQueue", "endpoint", null, null);
    temporalContext.setRequestId("request-id");
    CurrentNexusOperationContext.set(temporalContext);

    TemporalInterceptorMiddleware middleware =
        new TemporalInterceptorMiddleware(new WorkerInterceptor[0]);
    middleware.intercept(
        operationContext, OperationHandler.sync((context, details, input) -> input));
  }
}
