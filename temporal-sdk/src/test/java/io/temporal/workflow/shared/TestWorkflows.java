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

package io.temporal.workflow.shared;

import io.temporal.common.CronSchedule;
import io.temporal.workflow.*;
import java.util.List;

public class TestWorkflows {
  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void execute(ChildWorkflowCancellationType cancellationType);
  }

  @WorkflowInterface
  public interface TestWorkflow1 {

    @WorkflowMethod
    String execute(String taskQueue);
  }

  @WorkflowInterface
  public interface TestWorkflow2 {

    @WorkflowMethod(name = "testActivity")
    String execute(boolean useExternalService);

    @QueryMethod(name = "getTrace")
    List<String> getTrace();
  }

  @WorkflowInterface
  public interface TestChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface ITestChild {

    @WorkflowMethod
    String execute(String arg, int delay);
  }

  @WorkflowInterface
  public interface ITestNamedChild {

    @WorkflowMethod(name = "namedChild")
    String execute(String arg);
  }

  @WorkflowInterface
  public interface QueryableWorkflow {

    @WorkflowMethod
    String execute();

    @QueryMethod
    String getState();

    @SignalMethod(name = "testSignal")
    void mySignal(String value);
  }

  @WorkflowInterface
  public interface DeterminismFailingWorkflow {
    @WorkflowMethod
    void execute(String taskQueue);
  }

  @WorkflowInterface
  public interface SignalingChild {

    @WorkflowMethod
    String execute(String arg, String parentWorkflowId);
  }

  @WorkflowInterface
  public interface TestWorkflowRetry {

    @WorkflowMethod
    String execute(String testName);
  }

  @WorkflowInterface
  public interface TestWorkflowWithCronSchedule {
    @WorkflowMethod
    @CronSchedule("0 * * * *")
    String execute(String testName);
  }

  @WorkflowInterface
  public interface TestWorkflowSignaled {

    @WorkflowMethod
    String execute();

    @SignalMethod(name = "testSignal")
    void signal1(String arg);
  }
}
