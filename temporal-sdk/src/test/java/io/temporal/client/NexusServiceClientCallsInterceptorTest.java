package io.temporal.client;

import io.nexusrpc.client.ServiceClient;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.OperationStartDetails;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.common.interceptors.NexusServiceClientCallsInterceptor;
import io.temporal.common.interceptors.NexusServiceClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusServiceClientCallsInterceptorTest {
  private final AtomicInteger intercepted = new AtomicInteger();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(
                      new WorkflowClientInterceptorBase() {
                        @Override
                        public NexusServiceClientCallsInterceptor
                            nexusServiceClientCallsInterceptor(
                                NexusServiceClientCallsInterceptor next) {
                          return new NexusServiceClientCallsInterceptorBase(next) {
                            @Override
                            public StartOperationOutput startOperation(StartOperationInput input)
                                throws io.nexusrpc.OperationException {
                              intercepted.incrementAndGet();
                              return super.startOperation(input);
                            }
                          };
                        }
                      })
                  .validateAndBuildWithDefaults())
          .build();

  @Test
  public void interceptorIsInvoked() throws Exception {
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule.newNexusServiceClient(TestNexusServices.TestNexusService1.class);
    String result =
        serviceClient.executeOperation(TestNexusServices.TestNexusService1::operation, "World");
    Assert.assertEquals("Hello World", result);
    Assert.assertEquals(1, intercepted.get());
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (OperationContext ctx, OperationStartDetails details, String param) -> "Hello " + param);
    }
  }
}
