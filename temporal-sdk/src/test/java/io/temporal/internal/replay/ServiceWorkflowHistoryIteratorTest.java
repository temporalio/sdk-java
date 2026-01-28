package io.temporal.internal.replay;

import com.google.protobuf.ByteString;
import io.temporal.api.history.v1.History;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.testUtils.HistoryUtils;
import java.nio.charset.Charset;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class ServiceWorkflowHistoryIteratorTest {
  public static final ByteString NEXT_PAGE_TOKEN =
      ByteString.copyFrom("next token", Charset.defaultCharset());
  public static final ByteString EMPTY_HISTORY_PAGE =
      ByteString.copyFrom("empty history page token", Charset.defaultCharset());
  public static final ByteString NEXT_NEXT_PAGE_TOKEN =
      ByteString.copyFrom("next next token", Charset.defaultCharset());
  public static final ByteString EMPTY_PAGE_TOKEN =
      ByteString.copyFrom("empty page token", Charset.defaultCharset());

  /*
     This test Scenario verifies following things:
     1. hasNext() method makes a call to the server to retrieve workflow history when current
     history is empty and history token is available and cached the result.
     2. next() method reuses cached history when possible.
     3. hasNext() keeps fetching as long as the server returns a next page token.
     4. hasNext() fetches an empty page and return false.
     5. next() throws NoSuchElementException when neither history no history token is available.
  */
  @Test
  public void verifyHasNextIsFalseWhenHistoryIsEmpty() {
    PollWorkflowTaskQueueResponse workflowTask =
        PollWorkflowTaskQueueResponse.newBuilder().setNextPageToken(NEXT_PAGE_TOKEN).build();

    AtomicInteger timesCalledServer = new AtomicInteger(0);
    ServiceWorkflowHistoryIterator iterator =
        new ServiceWorkflowHistoryIterator(null, "default", workflowTask, null) {
          @Override
          GetWorkflowExecutionHistoryResponse queryWorkflowExecutionHistory() {
            timesCalledServer.incrementAndGet();
            try {
              History history = HistoryUtils.generateWorkflowTaskWithInitialHistory().getHistory();
              if (EMPTY_PAGE_TOKEN.equals(nextPageToken)) {
                return GetWorkflowExecutionHistoryResponse.newBuilder().build();
              } else if (EMPTY_HISTORY_PAGE.equals(nextPageToken)) {
                return GetWorkflowExecutionHistoryResponse.newBuilder()
                    .setNextPageToken(NEXT_NEXT_PAGE_TOKEN)
                    .build();
              } else if (NEXT_NEXT_PAGE_TOKEN.equals(nextPageToken)) {
                return GetWorkflowExecutionHistoryResponse.newBuilder()
                    .setHistory(history)
                    .setNextPageToken(EMPTY_PAGE_TOKEN)
                    .build();
              }
              return GetWorkflowExecutionHistoryResponse.newBuilder()
                  .setHistory(history)
                  .setNextPageToken(EMPTY_HISTORY_PAGE)
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
    Assert.assertTrue(iterator.hasNext());
    Assert.assertEquals(3, timesCalledServer.get());
    Assert.assertNotNull(iterator.next());
    Assert.assertTrue(iterator.hasNext());
    Assert.assertNotNull(iterator.next());
    Assert.assertTrue(iterator.hasNext());
    Assert.assertNotNull(iterator.next());
    Assert.assertFalse(iterator.hasNext());
    Assert.assertThrows(NoSuchElementException.class, iterator::next);
    Assert.assertEquals(4, timesCalledServer.get());
  }
}
