package io.temporal.client;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.payload.storage.InMemoryPayloadCache;
import io.temporal.payload.storage.PayloadCache;
import org.junit.Test;

/** Each client options class resolves its payload cache once and keeps it through copies. */
public class PayloadCacheClientOptionsTest {
  private final PayloadCache cache = new InMemoryPayloadCache();

  @Test
  public void workflowClientOptions() {
    assertSame(
        PayloadCache.disabled(), WorkflowClientOptions.newBuilder().build().getPayloadCache());
    assertSame(
        PayloadCache.disabled(),
        WorkflowClientOptions.newBuilder().validateAndBuildWithDefaults().getPayloadCache());
    assertSame(
        PayloadCache.disabled(),
        WorkflowClientOptions.newBuilder()
            .setPayloadCache(PayloadCache.disabled())
            .build()
            .getPayloadCache());

    WorkflowClientOptions withCache =
        WorkflowClientOptions.newBuilder().setPayloadCache(cache).build();
    assertSame(cache, withCache.getPayloadCache());
    assertSame(cache, WorkflowClientOptions.newBuilder(withCache).build().getPayloadCache());
    assertSame(
        cache,
        WorkflowClientOptions.newBuilder(withCache)
            .validateAndBuildWithDefaults()
            .getPayloadCache());
    assertNotEquals(WorkflowClientOptions.newBuilder().build(), withCache);
  }

  @Test
  public void activityClientOptions() {
    assertSame(
        PayloadCache.disabled(), ActivityClientOptions.newBuilder().build().getPayloadCache());
    assertSame(
        PayloadCache.disabled(),
        ActivityClientOptions.newBuilder()
            .setPayloadCache(PayloadCache.disabled())
            .build()
            .getPayloadCache());

    ActivityClientOptions withCache =
        ActivityClientOptions.newBuilder().setPayloadCache(cache).build();
    assertSame(cache, withCache.getPayloadCache());
    assertSame(cache, ActivityClientOptions.newBuilder(withCache).build().getPayloadCache());
    assertSame(cache, withCache.toBuilder().build().getPayloadCache());
    assertNotEquals(ActivityClientOptions.newBuilder().build(), withCache);
  }

  @Test
  public void scheduleClientOptions() {
    assertSame(
        PayloadCache.disabled(), ScheduleClientOptions.newBuilder().build().getPayloadCache());
    assertSame(
        PayloadCache.disabled(),
        ScheduleClientOptions.newBuilder()
            .setPayloadCache(PayloadCache.disabled())
            .build()
            .getPayloadCache());

    ScheduleClientOptions withCache =
        ScheduleClientOptions.newBuilder().setPayloadCache(cache).build();
    assertSame(cache, withCache.getPayloadCache());
    assertSame(cache, ScheduleClientOptions.newBuilder(withCache).build().getPayloadCache());
    assertSame(cache, withCache.toBuilder().build().getPayloadCache());
  }

  @Test
  public void nexusClientOptions() {
    assertSame(PayloadCache.disabled(), NexusClientOptions.newBuilder().build().getPayloadCache());
    assertSame(
        PayloadCache.disabled(),
        NexusClientOptions.newBuilder()
            .setPayloadCache(PayloadCache.disabled())
            .build()
            .getPayloadCache());

    NexusClientOptions withCache = NexusClientOptions.newBuilder().setPayloadCache(cache).build();
    assertSame(cache, withCache.getPayloadCache());
    assertSame(cache, NexusClientOptions.newBuilder(withCache).build().getPayloadCache());
  }
}
