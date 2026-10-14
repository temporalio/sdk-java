package io.temporal.internal.activity;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.common.TemporalCopyable;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor.ActivityOutput;
import io.temporal.internal.worker.ActivityTaskHandler;
import java.util.Optional;
import org.junit.Test;

public class ActivityTaskExecutorsCopyableResultTest {
  public interface TestActivity {
    Object result();
  }

  public static class Value implements TemporalCopyable<Value> {
    public int value;

    @Override
    public Value copy() {
      Value copy = new Value();
      copy.value = value;
      return copy;
    }
  }

  private ActivityTaskHandler.Result complete(Value value, DataConverter converter)
      throws Exception {
    ActivityInfoInternal info = mock(ActivityInfoInternal.class);
    when(info.isLocal()).thenReturn(true);
    return new ActivityTaskExecutors.POJOActivityImplementation(
            TestActivity.class.getMethod("result"), null, converter, null, null, null)
        .constructSuccessfulResultValue(info, new ActivityOutput(value), converter);
  }

  @Test
  public void copiesBeforeSerializingAndSerializesTheDeliveredCopy() throws Exception {
    Value original = new Value();
    DataConverter converter = mock(DataConverter.class);
    when(converter.toPayloads(any(Value.class)))
        .thenAnswer(
            invocation -> {
              Value serialized = invocation.getArgument(0);
              assertNotSame(original, serialized);
              // Mutating the original during serialization must not change either result.
              original.value = 9;
              return DefaultDataConverter.STANDARD_INSTANCE.toPayloads(serialized);
            });
    ActivityTaskHandler.Result result = complete(original, converter);
    Value live = (Value) result.getCopiedResult();
    verify(converter).toPayloads(same(live));
    Value recorded =
        DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
            0, Optional.of(result.getTaskCompleted().getResult()), Value.class, Value.class);
    assertEquals(0, live.value);
    assertEquals(live.value, recorded.value);
    live.value = 8;
    assertEquals(9, original.value);
    assertEquals(0, recorded.value);
  }

  @Test
  public void copyFailurePropagatesWithoutSerialization() throws Exception {
    Value value = spy(new Value());
    IllegalStateException failure = new IllegalStateException("Copy failed.");
    doThrow(failure).when(value).copy();
    DataConverter converter = mock(DataConverter.class);
    try {
      complete(value, converter);
      fail("Expected copy failure.");
    } catch (IllegalStateException e) {
      assertSame(failure, e);
    }
    verifyNoInteractions(converter);
  }
}
