package io.temporal;

import static io.temporal.ActivityImpl.ACTIVITY_RESULT;
import static io.temporal.PortUtils.getFreePort;
import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.payload.codec.AbstractRemoteDataEncoderCodec;
import io.temporal.payload.codec.OkHttpRemoteDataEncoderCodec;
import io.temporal.payload.codec.ZlibPayloadCodec;
import io.temporal.rde.servlet.RDEServlet4;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.Collections;
import okhttp3.OkHttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class RDEServlet4FunctionalTest {
  private static final ActivityImpl activitiesImpl = new ActivityImpl();

  private static final OkHttpClient okHttpClient = new OkHttpClient();

  private static final int serverPort = getFreePort();

  private static Server server = new Server();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ActivityWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      new CodecDataConverter(
                          DefaultDataConverter.STANDARD_INSTANCE,
                          Collections.singletonList(
                              new OkHttpRemoteDataEncoderCodec(
                                  okHttpClient, "http://localhost:" + serverPort))))
                  .build())
          .build();

  @BeforeClass
  public static void beforeClass() throws Exception {
    server = new Server(serverPort);
    ServletHandler handler = new ServletHandler();
    server.setHandler(handler);
    handler.addServletWithMapping(
        ConfiguredRDEServlet4.class, AbstractRemoteDataEncoderCodec.ENCODE_PATH_POSTFIX);
    handler.addServletWithMapping(
        ConfiguredRDEServlet4.class, AbstractRemoteDataEncoderCodec.DECODE_PATH_POSTFIX);
    server.start();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    server.stop();
  }

  public static class ConfiguredRDEServlet4 extends RDEServlet4 {
    public ConfiguredRDEServlet4() {
      super(Collections.singletonList(new ZlibPayloadCodec()));
    }
  }

  @Test
  public void testSimpleActivityWorkflow() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflowStringArg workflow =
        client.newWorkflowStub(
            TestWorkflowStringArg.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    int result = workflow.execute("input1");
    assertEquals(ACTIVITY_RESULT, result);
  }
}
