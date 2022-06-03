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

package io.temporal.internal.replay;

import com.google.protobuf.ByteString;
import io.temporal.api.history.v1.History;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.testUtils.HistoryUtils;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class WorkflowHistoryIteratorTest {

  public static final ByteString EMPTY_PAGE_TOKEN =
      ByteString.copyFrom("empty page token", Charset.defaultCharset());
  public static final ByteString NEXT_PAGE_TOKEN =
      ByteString.copyFrom("next token", Charset.defaultCharset());

  /*
     This test Scenario verifies following things:
     1. hasNext() method makes a call to the server to retrieve workflow history when current
     history is empty and history token is available and cached the result.
     2. next() method reuses cached history when possible.
     3. hasNext() fetches an empty page and return false.
     4. next() throws NoSuchElementException when neither history no history token is available.
  */
  @Test
  public void verifyHasNextIsFalseWhenHistoryIsEmpty() {
    PollWorkflowTaskQueueResponse workflowTask =
        PollWorkflowTaskQueueResponse.newBuilder().setNextPageToken(NEXT_PAGE_TOKEN).build();

    AtomicInteger timesCalledServer = new AtomicInteger(0);
    WorkflowHistoryIterator iterator =
        new WorkflowHistoryIterator(null, "default", workflowTask, Duration.ofSeconds(10), null) {
          GetWorkflowExecutionHistoryResponse queryWorkflowExecutionHistory() {
            timesCalledServer.incrementAndGet();
            try {
              if (EMPTY_PAGE_TOKEN.equals(nextPageToken)) {
                return GetWorkflowExecutionHistoryResponse.newBuilder().build();
              }
              History history = HistoryUtils.generateWorkflowTaskWithInitialHistory().getHistory();
              return GetWorkflowExecutionHistoryResponse.newBuilder()
                  .setHistory(history)
                  .setNextPageToken(EMPTY_PAGE_TOKEN)
                  .build();
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
    Assert.assertEquals(1, timesCalledServer.get());
    Assert.assertFalse(iterator.hasNext());
    Assert.assertEquals(2, timesCalledServer.get());
    Assert.assertThrows(NoSuchElementException.class, iterator::next);
    Assert.assertEquals(2, timesCalledServer.get());
  }
}
