package io.temporal.internal.replay;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.internal.payload.storage.ExternalStorageNotConfiguredException;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.testUtils.HistoryUtils;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
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

  @Test
  public void resolvesExternalStorageReferencesInFetchedPages() {
    ExternalStorageRunner storage = inMemoryStorage();
    History inline = historyWithInput(payload("big-input"));
    History stored = storage.storeBlocking(inline, null);
    Assert.assertNotEquals(
        "stored history should hold a reference, not the inline payload", inline, stored);

    ServiceWorkflowHistoryIterator iterator = fetchingIterator(stored, storage);

    HistoryEvent event = iterator.next();
    Assert.assertEquals(
        payload("big-input"),
        event.getWorkflowExecutionStartedEventAttributes().getInput().getPayloads(0));
  }

  @Test
  public void failsLoudWhenAFetchedPageHasAReferenceAndStorageIsNotConfigured() {
    History stored = inMemoryStorage().storeBlocking(historyWithInput(payload("big-input")), null);

    ServiceWorkflowHistoryIterator iterator = fetchingIterator(stored, null);

    Assert.assertThrows(ExternalStorageNotConfiguredException.class, iterator::hasNext);
  }

  private static ServiceWorkflowHistoryIterator fetchingIterator(
      History page, ExternalStorageRunner storage) {
    PollWorkflowTaskQueueResponse workflowTask =
        PollWorkflowTaskQueueResponse.newBuilder().setNextPageToken(NEXT_PAGE_TOKEN).build();
    return new ServiceWorkflowHistoryIterator(null, "default", workflowTask, null, storage) {
      @Override
      GetWorkflowExecutionHistoryResponse queryWorkflowExecutionHistory() {
        return GetWorkflowExecutionHistoryResponse.newBuilder().setHistory(page).build();
      }
    };
  }

  private static ExternalStorageRunner inMemoryStorage() {
    return ExternalStorageRunner.create(
        ExternalStorage.newBuilder()
            .setDriver(new InMemoryDriver())
            .setPayloadSizeThreshold(0)
            .build());
  }

  private static History historyWithInput(Payload payload) {
    return History.newBuilder()
        .addEvents(
            HistoryEvent.newBuilder()
                .setWorkflowExecutionStartedEventAttributes(
                    WorkflowExecutionStartedEventAttributes.newBuilder()
                        .setInput(Payloads.newBuilder().addPayloads(payload))))
        .build();
  }

  private static Payload payload(String data) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(data)).build();
  }

  private static final class InMemoryDriver implements StorageDriver {
    private final Map<String, Payload> objects = new HashMap<>();
    private int counter = 0;

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = "k-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
