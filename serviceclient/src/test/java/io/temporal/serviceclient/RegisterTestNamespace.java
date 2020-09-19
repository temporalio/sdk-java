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

package io.temporal.serviceclient;

import com.google.protobuf.util.Durations;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;

/**
 * Waits for local service to become available and registers namespace specified through
 * TEMPORAL_NAMESPACE (or default UnitTest). Registers the namespace only if USE_DOCKER_SERVICE
 * environment variable is true.
 */
public class RegisterTestNamespace {

  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));
  private static final String serviceAddress = System.getenv("TEMPORAL_SERVICE_ADDRESS");

  public static final String NAMESPACE;

  static {
    String ns = System.getenv("TEMPORAL_NAMESPACE");
    NAMESPACE = ns == null ? "UnitTest" : ns;
  }

  public static void main(String[] args) throws InterruptedException {
    if (!useDockerService) {
      return;
    }

    WorkflowServiceStubsOptions.Builder options = WorkflowServiceStubsOptions.newBuilder();
    if (serviceAddress != null) {
      options.setTarget(serviceAddress);
    }
    WorkflowServiceStubs service = WorkflowServiceStubs.newInstance(options.build());
    RegisterNamespaceRequest request =
        RegisterNamespaceRequest.newBuilder()
            .setName(NAMESPACE)
            .setWorkflowExecutionRetentionPeriod(Durations.fromDays(1))
            .build();
    while (true) {
      try {
        service.blockingStub().registerNamespace(request);
        break;
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
          break;
        }
        if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED
            || e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
          e.printStackTrace();
          Thread.sleep(500);
        }
        continue;
      } catch (Throwable e) {
        e.printStackTrace();
        System.exit(1);
      }
    }
    System.exit(0);
  }
}
