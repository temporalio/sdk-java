package io.temporal.internal.nexus;

import static org.mockito.Mockito.mock;

import com.uber.m3.tally.NoopScope;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

  // NexusTaskHandlerImpl shares one middleware between all concurrent Nexus tasks of a worker, so
  // each intercept call must wrap its own handler, not the handler of a concurrent call.
  @Test
  public void concurrentInterceptKeepsOwnHandler() throws Exception {
    TemporalInterceptorMiddleware middleware =
        new TemporalInterceptorMiddleware(new WorkerInterceptor[] {});
    OperationContext context = mock(OperationContext.class);
    OperationStartDetails details = mock(OperationStartDetails.class);
    WorkflowClient client = mock(WorkflowClient.class);

    ExecutorService executor = Executors.newFixedThreadPool(4);
    List<Future<Integer>> misrouted = new ArrayList<>();
    for (int t = 0; t < 4; t++) {
      String operation = "operation" + t;
      OperationHandler<Object, Object> handler = OperationHandler.sync((c, d, i) -> operation);
      misrouted.add(
          executor.submit(
              () -> {
                CurrentNexusOperationContext.set(
                    new InternalNexusOperationContext(
                        "namespace", "taskQueue", "endpoint", new NoopScope(), client));
                int count = 0;
                for (int i = 0; i < 10_000; i++) {
                  Object result =
                      middleware
                          .intercept(context, handler)
                          .start(context, details, null)
                          .getSyncResult();
                  if (!operation.equals(result)) {
                    count++;
                  }
                }
                CurrentNexusOperationContext.unset();
                return count;
              }));
    }
    try {
      for (Future<Integer> f : misrouted) {
        Assert.assertEquals(0, (int) f.get());
      }
    } finally {
      executor.shutdownNow();
    }
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
