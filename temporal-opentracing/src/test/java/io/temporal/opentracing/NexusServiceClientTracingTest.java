package io.temporal.opentracing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.nexusrpc.OperationInfo;
import io.nexusrpc.OperationState;
import io.nexusrpc.client.OperationHandle;
import io.nexusrpc.client.ServiceClient;
import io.nexusrpc.client.StartOperationResponse;
import io.nexusrpc.handler.*;
import io.opentracing.Scope;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class NexusServiceClientTracingTest {
  private static final String NEXUS_OPERATION_TOKEN = "test-operation-token";
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
    ServiceClient<NexusOperationTest.TestNexusService> serviceClient =
        testWorkflowRule.newNexusServiceClient(NexusOperationTest.TestNexusService.class);

    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      StartOperationResponse<String> result =
          serviceClient.startOperation(NexusOperationTest.TestNexusService::operation, "World");
      assertTrue(result instanceof StartOperationResponse.Async);
      OperationHandle<String> handle = ((StartOperationResponse.Async<String>) result).getHandle();
      handle.cancel();
      assertEquals("Hello World", handle.fetchResult());
      handle.fetchInfo();
    } finally {
      span.finish();
    }

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());
    // Verify the start span from the client and the handler
    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");
    MockSpan startSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), startSpan.parentId());
    assertEquals("ClientStartNexusOperation:TestNexusService/operation", startSpan.operationName());

    MockSpan runSpan = spansHelper.getByParentSpan(startSpan).get(0);
    assertEquals(startSpan.context().spanId(), runSpan.parentId());
    assertEquals(
        "RunStartNexusOperationHandler:TestNexusService/operation", runSpan.operationName());

    // Verify the cancel span from the client and the handler
    MockSpan clientCancelSpan = spansHelper.getByParentSpan(clientSpan).get(1);
    assertEquals(clientSpan.context().spanId(), clientCancelSpan.parentId());
    assertEquals(
        "ClientCancelNexusOperation:TestNexusService/operation", clientCancelSpan.operationName());

    MockSpan handlerCancelSpan = spansHelper.getByParentSpan(clientCancelSpan).get(0);
    assertEquals(clientCancelSpan.context().spanId(), handlerCancelSpan.parentId());
    assertEquals(
        "RunCancelNexusOperationHandler:TestNexusService/operation",
        handlerCancelSpan.operationName());

    // Verify the fetchResult span from the client and the handler
    MockSpan fetchResultSpan = spansHelper.getByParentSpan(clientSpan).get(2);
    assertEquals(clientSpan.context().spanId(), fetchResultSpan.parentId());
    assertEquals(
        "ClientFetchNexusOperationResult:TestNexusService/operation",
        fetchResultSpan.operationName());

    MockSpan handlerFetchResultSpan = spansHelper.getByParentSpan(fetchResultSpan).get(0);
    assertEquals(fetchResultSpan.context().spanId(), handlerFetchResultSpan.parentId());
    assertEquals(
        "RunFetchNexusOperationResultHandler:TestNexusService/operation",
        handlerFetchResultSpan.operationName());

    // Verify the fetchInfo span from the client and the handler
    MockSpan fetchInfoSpan = spansHelper.getByParentSpan(clientSpan).get(3);
    assertEquals(clientSpan.context().spanId(), fetchInfoSpan.parentId());
    assertEquals(
        "ClientFetchNexusOperationInfo:TestNexusService/operation", fetchInfoSpan.operationName());

    MockSpan handlerFetchInfoSpan = spansHelper.getByParentSpan(fetchInfoSpan).get(0);
    assertEquals(fetchInfoSpan.context().spanId(), handlerFetchInfoSpan.parentId());
    assertEquals(
        "RunFetchNexusOperationInfoHandler:TestNexusService/operation",
        handlerFetchInfoSpan.operationName());
  }

  @ServiceImpl(service = NexusOperationTest.TestNexusService.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return new OperationHandler<String, String>() {
        @Override
        public OperationStartResult<String> start(
            OperationContext context, OperationStartDetails details, String param)
            throws HandlerException {
          return OperationStartResult.async(NEXUS_OPERATION_TOKEN);
        }

        @Override
        public String fetchResult(OperationContext context, OperationFetchResultDetails details)
            throws HandlerException {
          return "Hello World";
        }

        @Override
        public OperationInfo fetchInfo(OperationContext context, OperationFetchInfoDetails details)
            throws HandlerException {
          return OperationInfo.newBuilder()
              .setState(OperationState.SUCCEEDED)
              .setToken(NEXUS_OPERATION_TOKEN)
              .build();
        }

        @Override
        public void cancel(OperationContext context, OperationCancelDetails details)
            throws HandlerException {}
      };
    }
  }
}
