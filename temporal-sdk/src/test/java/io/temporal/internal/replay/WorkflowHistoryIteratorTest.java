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

package io.temporal.internal.replay;

import com.google.protobuf.ByteString;
import io.temporal.api.history.v1.History;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.HistoryUtils;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WorkflowHistoryIteratorTest {

  private TestWorkflowService testService;
  private WorkflowServiceStubs service;

  @Before
  public void setUp() {
    testService = new TestWorkflowService(true);
    service = testService.newClientStub();
  }

  @After
  public void tearDown() {
    service.shutdownNow();
    try {
      service.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    testService.close();
  }

  /*
     This test Scenario verifies following things:
     1. hasNext() method makes a call to the server to retrieve workflow history when current
     history is empty and history token is available and cached the result.
     2. next() method reuses cached history when possible.
     3. next() throws NoSuchElementException when neither history no history token is available.
  */
  @Test
  public void verifyHasNextIsFalseWhenHistoryIsEmpty() {
    PollWorkflowTaskQueueResponse workflowTask =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setNextPageToken(ByteString.copyFrom("next token", Charset.defaultCharset()))
            .build();

    AtomicInteger timesCalledServer = new AtomicInteger(0);
    WorkflowHistoryIterator iterator =
        new WorkflowHistoryIterator(
            service, "default", workflowTask, Duration.ofSeconds(10), null) {
          GetWorkflowExecutionHistoryResponse queryWorkflowExecutionHistory() {
            timesCalledServer.incrementAndGet();
            try {
              History history = HistoryUtils.generateWorkflowTaskWithInitialHistory().getHistory();
              return GetWorkflowExecutionHistoryResponse.newBuilder().setHistory(history).build();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        };
    Assert.assertEquals(0, timesCalledServer.get());
    Assert.assertTrue(iterator.hasNext());
    Assert.assertEquals(1, timesCalledServer.get());
    Assert.assertNotNull(iterator.next());
    Assert.assertTrue(iterator.hasNext());
    Assert.assertNotNull(iterator.next());
    Assert.assertTrue(iterator.hasNext());
    Assert.assertNotNull(iterator.next());
    Assert.assertFalse(iterator.hasNext());
    Assert.assertThrows(NoSuchElementException.class, iterator::next);
    Assert.assertEquals(1, timesCalledServer.get());
  }
}
