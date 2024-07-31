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

package io.temporal.internal.testservice;

import io.grpc.BindableService;
import java.io.Closeable;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestServicesStarter implements Closeable {
  private final SelfAdvancingTimerImpl selfAdvancingTimer;
  private final TestVisibilityStore visibilityStore = new TestVisibilityStoreImpl();
  private final TestNexusEndpointStore nexusEndpointStore = new TestNexusEndpointStoreImpl();
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
    this.selfAdvancingTimer =
        new SelfAdvancingTimerImpl(initialTimeMillis, Clock.systemDefaultZone());
    this.workflowStore = new TestWorkflowStoreImpl(this.selfAdvancingTimer);
    this.operatorService = new TestOperatorService(this.visibilityStore, this.nexusEndpointStore);
    this.testService =
        new TestService(this.workflowStore, this.selfAdvancingTimer, lockTimeSkipping);
    this.workflowService =
        new TestWorkflowService(this.workflowStore, this.visibilityStore, this.selfAdvancingTimer);
    this.services = Arrays.asList(this.operatorService, this.testService, this.workflowService);
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
