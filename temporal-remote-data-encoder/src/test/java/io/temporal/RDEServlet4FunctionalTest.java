/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
