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

package io.temporal.internal.testservice;

import io.grpc.BindableService;
import java.io.Closeable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestServicesStarter implements Closeable {
  private final TestVisibilityStore visibilityStore = new TestVisibilityStoreImpl();
  private final TestWorkflowStore workflowStore;

  private final TestOperatorService operatorService;
  private final TestWorkflowService workflowService;
  private final TestService testService;
  private final List<BindableService> services;
  /**
   * @param lockTimeSkipping true if the time skipping should be locked (disabled) by default after
   *     creation of the server
   * @param initialTimeMillis initial timestamp for the test server, {@link
   *     System#currentTimeMillis()} will be used if 0.
   */
  public TestServicesStarter(boolean lockTimeSkipping, long initialTimeMillis) {
    this.workflowStore = new TestWorkflowStoreImpl(initialTimeMillis);
    this.operatorService = new TestOperatorService(visibilityStore);
    this.testService = new TestService(workflowStore, lockTimeSkipping);
    this.workflowService = new TestWorkflowService(workflowStore, visibilityStore);
    this.services = Arrays.asList(operatorService, testService, workflowService);
  }

  @Override
  public void close() {
    workflowService.close();
    operatorService.close();
    testService.close();
    visibilityStore.close();
  }

  public TestOperatorService getOperatorService() {
    return operatorService;
  }

  public TestWorkflowService getWorkflowService() {
    return workflowService;
  }

  public TestService getTestService() {
    return testService;
  }

  public List<BindableService> getServices() {
    return Collections.unmodifiableList(services);
  }
}
