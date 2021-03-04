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

package io.temporal.testing;

import static io.temporal.client.WorkflowClient.QUERY_TYPE_STACK_TRACE;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.io.CharSink;
import com.google.common.io.Files;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowQueryException;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SDKTestWorkflowRule extends TestWorkflowRule {

  public static final String BINARY_CHECKSUM = "testChecksum";
  public static final String ANNOTATION_TASK_QUEUE = "WorkflowTest-testExecute[Docker]";
  public static final String UUID_REGEXP =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  // Enable to regenerate JsonFiles used for replay testing.
  public static final boolean REGENERATE_JSON_FILES = false;
  private static final List<ScheduledFuture<?>> DELAYED_CALLBACKS = new ArrayList<>();
  private static final ScheduledExecutorService SCHEDULED_EXECUTOR =
      new ScheduledThreadPoolExecutor(1);
  private static final Logger log = LoggerFactory.getLogger(SDKTestWorkflowRule.class);

  protected SDKTestWorkflowRule(Builder builder) {
    super(builder);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder extends TestWorkflowRule.Builder {

    public Builder() {}

    @Override
    public SDKTestWorkflowRule build() {
      return new SDKTestWorkflowRule(this);
    }
  }

  /** Used to ensure that workflow first workflow task is executed. */
  public static void waitForOKQuery(WorkflowStub stub) {
    while (true) {
      try {
        String stackTrace = stub.query(QUERY_TYPE_STACK_TRACE, String.class);
        if (!stackTrace.isEmpty()) {
          break;
        }
      } catch (WorkflowQueryException e) {
      }
    }
  }

  public static void regenerateHistoryForReplay(
      WorkflowServiceStubs service, WorkflowExecution execution, String fileName) {
    if (REGENERATE_JSON_FILES) {
      GetWorkflowExecutionHistoryRequest request =
          GetWorkflowExecutionHistoryRequest.newBuilder()
              .setNamespace(TestWorkflowRule.NAMESPACE)
              .setExecution(execution)
              .build();
      GetWorkflowExecutionHistoryResponse response =
          service.blockingStub().getWorkflowExecutionHistory(request);
      WorkflowExecutionHistory history = new WorkflowExecutionHistory(response.getHistory());
      String json = history.toPrettyPrintedJson();
      String projectPath = System.getProperty("user.dir");
      String resourceFile = projectPath + "/src/test/resources/" + fileName + ".json";
      File file = new File(resourceFile);
      CharSink sink = Files.asCharSink(file, Charsets.UTF_8);
      try {
        sink.write(json);
      } catch (IOException e) {
        Throwables.propagateIfPossible(e, RuntimeException.class);
      }
      log.info("Regenerated history file: " + resourceFile);
    }
  }

  // TODO: Refactor testEnvironment to support testing through real service to avoid this
  // switches
  public void registerDelayedCallback(Duration delay, Runnable r) {
    if (TestWorkflowRule.USE_EXTERNAL_SERVICE) {
      ScheduledFuture<?> result =
          SCHEDULED_EXECUTOR.schedule(r, delay.toMillis(), TimeUnit.MILLISECONDS);
      DELAYED_CALLBACKS.add(result);
    } else {
      this.getTestEnvironment().registerDelayedCallback(delay, r);
    }
  }

  // TODO(vkoby) double check that both super and this shutdown execute
  @Override
  protected void shutdown() throws Throwable {
    super.shutdown();
    for (ScheduledFuture<?> result : DELAYED_CALLBACKS) {
      if (result.isDone() && !result.isCancelled()) {
        try {
          result.get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
          throw e.getCause();
        }
      }
    }
  }

  public void sleep(Duration d) {
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      try {
        Thread.sleep(d.toMillis());
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted", e);
      }
    } else {
      this.getTestEnvironment().sleep(d);
    }
  }
}
