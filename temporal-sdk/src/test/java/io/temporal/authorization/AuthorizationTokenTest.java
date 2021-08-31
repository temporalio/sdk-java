/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.authorization;

import static org.junit.Assert.*;

import io.grpc.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.testing.WorkflowTestingTest;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthorizationTokenTest {

  private static final Logger log = LoggerFactory.getLogger(AuthorizationTokenTest.class);
  private static final String TASK_QUEUE = "test-workflow";
  private static final String AUTH_TOKEN = "Bearer <token>";

  private TestWorkflowEnvironment testEnvironment;

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println(testEnvironment.getDiagnostics());
        }
      };

  private final List<GrpcRequest> loggedRequests = new ArrayList<>();

  @Before
  public void setUp() {
    loggedRequests.clear();
    WorkflowServiceStubsOptions stubOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .addGrpcClientInterceptor(
                new ClientInterceptor() {
                  @Override
                  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                    return new LoggingClientCall<>(method, next.newCall(method, callOptions));
                  }

                  class LoggingClientCall<ReqT, RespT>
                      extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

                    private final MethodDescriptor<ReqT, RespT> method;

                    LoggingClientCall(
                        MethodDescriptor<ReqT, RespT> method, ClientCall<ReqT, RespT> call) {
                      super(call);
                      this.method = method;
                    }

                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                      loggedRequests.add(
                          new GrpcRequest(
                              method.getBareMethodName(),
                              headers.get(
                                  AuthorizationGrpcMetadataProvider.AUTHORIZATION_HEADER_KEY)));
                      super.start(responseListener, headers);
                    }
                  }
                })
            .addGrpcMetadataProvider(new AuthorizationGrpcMetadataProvider(() -> AUTH_TOKEN))
            .build();

    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setContextPropagators(
                        Collections.singletonList(new WorkflowTestingTest.TestContextPropagator()))
                    .build())
            .setWorkflowServiceStubsOptions(stubOptions)
            .build();

    testEnvironment = TestWorkflowEnvironment.newInstance(options);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void allRequestsShouldHaveAnAuthToken() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(EmptyWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflows.TestWorkflow1 workflow =
        client.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("TestWorkflow1-input1", result);

    assertFalse(loggedRequests.isEmpty());
    for (GrpcRequest grpcRequest : loggedRequests) {
      assertEquals(
          "All requests should have an auth token", AUTH_TOKEN, grpcRequest.authTokenValue);
    }
  }

  public static class EmptyWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.sleep(Duration.ofMinutes(5)); // test time skipping
      return Workflow.getInfo().getWorkflowType() + "-" + input;
    }
  }

  class GrpcRequest {
    String methodName;
    String authTokenValue;

    public GrpcRequest(String methodName, String authTokenValue) {
      this.methodName = methodName;
      this.authTokenValue = authTokenValue;
    }
  }
}
