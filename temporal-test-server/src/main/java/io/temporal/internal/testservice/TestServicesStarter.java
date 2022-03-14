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

import java.io.Closeable;

public class TestServicesStarter implements Closeable {
  private final TestVisibilityStore visibilityStore = new TestVisibilityStoreImpl();
  private final TestOperatorService operatorService;
  private final TestWorkflowService workflowService;

  /**
   * @param lockTimeSkipping true if the time skipping should be locked (disabled) by default after
   *     creation of the server
   * @param initialTimeMillis initial timestamp for the test server, {@link
   *     System#currentTimeMillis()} will be used if 0.
   */
  public TestServicesStarter(boolean lockTimeSkipping, long initialTimeMillis) {
    this.operatorService = new TestOperatorService(visibilityStore);
    this.workflowService = new TestWorkflowService(initialTimeMillis, visibilityStore);
    if (lockTimeSkipping) {
      this.workflowService.lockTimeSkipping("TestServicesStarter constructor");
    }
  }

  @Override
  public void close() {
    workflowService.close();
    operatorService.close();
    visibilityStore.close();
  }

  public TestOperatorService getOperatorService() {
    return operatorService;
  }

  public TestWorkflowService getWorkflowService() {
    return workflowService;
  }
}
