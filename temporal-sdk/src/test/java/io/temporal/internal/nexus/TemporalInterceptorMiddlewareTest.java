package io.temporal.internal.nexus;

import static org.mockito.Mockito.mock;

import com.uber.m3.tally.NoopScope;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.WorkerInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Assert;
import org.junit.Test;

public class TemporalInterceptorMiddlewareTest {

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
}
