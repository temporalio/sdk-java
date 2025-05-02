package io.temporal;

import static io.temporal.ActivityImpl.ACTIVITY_RESULT;
import static io.temporal.PortUtils.getFreePort;
import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.payload.codec.OkHttpRemoteDataEncoderCodec;
import io.temporal.payload.codec.ZlibPayloadCodec;
import io.temporal.rde.httpserver.RDEHttpServer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.Collections;
import okhttp3.OkHttpClient;
import org.junit.*;

public class RDEHttpServerFunctionalTest {
  private static final ActivityImpl activitiesImpl = new ActivityImpl();

  private static final OkHttpClient okHttpClient = new OkHttpClient();

  private static final int serverPort = getFreePort();

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

  private RDEHttpServer rdeServer;

  @Before
  public void setUp() throws Exception {
    rdeServer = new RDEHttpServer(Collections.singletonList(new ZlibPayloadCodec()), serverPort);
    rdeServer.start();
  }

  @After
  public void tearDown() {
    rdeServer.close();
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
