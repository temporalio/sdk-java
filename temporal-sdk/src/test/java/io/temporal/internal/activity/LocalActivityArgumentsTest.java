package io.temporal.internal.activity;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.common.Copyable;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.CopyableUtils;
import org.junit.Test;

public class LocalActivityArgumentsTest {
  private static class Value implements Copyable<Value> {
    int value;

    @Override
    public Value copy() {
      Value copy = new Value();
      copy.value = value;
      return copy;
    }
  }

  @Test
  public void capturesSnapshotAndCopiesEachAttemptIncludingNullSlots() {
    Value original = new Value();
    Object[] input = {original, null};
    LocalActivityArguments snapshot = LocalActivityArguments.capture(input);
    original.value = 1;
    input[0] = null;
    Object[] first = snapshot.copy();
    ((Value) first[0]).value = 2;
    first[1] = original;
    Object[] second = snapshot.copy();
    assertNotSame(first[0], second[0]);
    assertEquals(0, ((Value) second[0]).value);
    assertNull(second[1]);
    assertTrue(snapshot.canCopyTo(new Class<?>[] {Value.class, Object.class}));
    assertFalse(snapshot.canCopyTo(new Class<?>[] {String.class, Object.class}));
    assertFalse(snapshot.canCopyTo(new Class<?>[] {Value.class, int.class}));
    assertFalse(snapshot.canCopyTo(new Class<?>[0]));
  }

  @Test
  public void activityInputSelectsOneArgumentSource() {
    DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;
    PollActivityTaskQueueResponse response =
        PollActivityTaskQueueResponse.newBuilder()
            .setInput(converter.toPayloads("serialized").get())
            .build();
    Value original = new Value();
    ActivityInput copied =
        new ActivityInput(response, LocalActivityArguments.capture(new Object[] {original}));
    DataConverter unusedConverter = mock(DataConverter.class);
    Class<?>[] copyTypes = {Value.class};
    assertNotSame(original, copied.getArguments(copyTypes, copyTypes, unusedConverter)[0]);
    verifyNoInteractions(unusedConverter);

    Class<?>[] serializedTypes = {String.class};
    assertArrayEquals(
        new Object[] {"serialized"},
        new ActivityInput(response, null)
            .getArguments(serializedTypes, serializedTypes, converter));
  }

  @Test
  public void immutableCopiesMayReturnThisButMixedBatchesMustNotCopy() {
    Value immutable =
        new Value() {
          @Override
          public Value copy() {
            return this;
          }
        };
    assertSame(immutable, LocalActivityArguments.capture(new Object[] {immutable}).copy()[0]);
    Copyable<Value> mustNotCopy =
        () -> {
          throw new AssertionError("Mixed batches must use serialization.");
        };
    assertNull(LocalActivityArguments.capture(new Object[] {mustNotCopy, "ordinary"}));
  }

  @Test(expected = NullPointerException.class)
  public void rejectsNullCopies() {
    CopyableUtils.copy((Copyable<Value>) () -> null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsCopiesOfAnotherType() {
    CopyableUtils.copy((Copyable<Value>) Value::new);
  }
}
