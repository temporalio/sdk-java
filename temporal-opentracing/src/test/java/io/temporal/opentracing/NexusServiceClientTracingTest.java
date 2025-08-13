package io.temporal.opentracing;

import static org.junit.Assert.assertEquals;

import io.nexusrpc.client.ServiceClient;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.OperationStartDetails;
import io.nexusrpc.handler.ServiceImpl;
import io.opentracing.Scope;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.shared.TestNexusServices;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class NexusServiceClientTracingTest {
  private final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  private final OpenTracingOptions OT_OPTIONS =
      OpenTracingOptions.newBuilder().setTracer(mockTracer).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTracingClientInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @Test
  public void testTracing() throws Exception {
    MockSpan span = mockTracer.buildSpan("ClientFunction").start();
    ServiceClient<TestNexusServices.TestNexusService1> serviceClient =
        testWorkflowRule.newNexusServiceClient(TestNexusServices.TestNexusService1.class);

    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      String result =
          serviceClient.executeOperation(TestNexusServices.TestNexusService1::operation, "World");
      assertEquals("Hello World", result);
    } finally {
      span.finish();
    }

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());
    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");
    MockSpan startSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), startSpan.parentId());
    assertEquals("StartNexusOperation:TestNexusService1/operation", startSpan.operationName());

    MockSpan runSpan = spansHelper.getByParentSpan(startSpan).get(0);
    assertEquals(startSpan.context().spanId(), runSpan.parentId());
    assertEquals(
        "RunStartNexusOperationHandler:TestNexusService1/operation", runSpan.operationName());
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
