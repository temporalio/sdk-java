package io.temporal.workflow.nexus;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import io.temporal.api.nexus.v1.EndpointTarget;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointResponse;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import org.junit.After;
import org.junit.Before;

public abstract class BaseNexusTest {

  protected abstract SDKTestWorkflowRule getTestWorkflowRule();

  Endpoint endpoint;

  public static String getEndpointName() {
    return "test-endpoint-" + Workflow.getInfo().getTaskQueue();
  }

  @Before
  public void setUp() {
    endpoint =
        createTestEndpoint(
            getTestEndpointSpecBuilder("test-endpoint-" + getTestWorkflowRule().getTaskQueue()));
  }

  @After
  public void tearDown() {
    getTestWorkflowRule()
        .getTestEnvironment()
        .getOperatorServiceStubs()
        .blockingStub()
        .deleteNexusEndpoint(
            io.temporal.api.operatorservice.v1.DeleteNexusEndpointRequest.newBuilder()
                .setId(endpoint.getId())
                .setVersion(endpoint.getVersion())
                .build());
  }

  private EndpointSpec.Builder getTestEndpointSpecBuilder(String name) {
    return EndpointSpec.newBuilder()
        .setName(name)
        .setDescription(Payload.newBuilder().setData(ByteString.copyFromUtf8("test endpoint")))
        .setTarget(
            EndpointTarget.newBuilder()
                .setWorker(
                    EndpointTarget.Worker.newBuilder()
                        .setNamespace(getTestWorkflowRule().getTestEnvironment().getNamespace())
                        .setTaskQueue(getTestWorkflowRule().getTaskQueue())));
  }

  private Endpoint createTestEndpoint(EndpointSpec.Builder spec) {
    CreateNexusEndpointResponse resp =
        getTestWorkflowRule()
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .createNexusEndpoint(CreateNexusEndpointRequest.newBuilder().setSpec(spec).build());
    return resp.getEndpoint();
  }
}
