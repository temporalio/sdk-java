package io.temporal.internal.activity;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/** One source of activity arguments: serialized payloads or a captured copyable snapshot. */
final class ActivityInput {
  // A cached serialized view of the input, materialized only if needed.
  private @Nullable Optional<Payloads> payloads;
  private final @Nullable LocalActivityArguments snapshot;

  ActivityInput(
      PollActivityTaskQueueResponseOrBuilder response, @Nullable LocalActivityArguments snapshot) {
    this.snapshot = snapshot;
    this.payloads =
        response.hasInput()
            ? Optional.of(response.getInput())
            : snapshot == null ? Optional.empty() : null;
  }

  Object[] getArguments(Class<?>[] types, Type[] genericTypes, DataConverter converter) {
    if (snapshot != null && snapshot.canCopyTo(types, genericTypes)) {
      return snapshot.copy();
    }
    return converter.fromPayloads(toPayloads(converter), types, genericTypes);
  }

  private Optional<Payloads> toPayloads(DataConverter converter) {
    if (payloads == null) {
      payloads = snapshot.toPayloads(converter);
    }
    return payloads;
  }

  EncodedValues getEncodedValues(DataConverter converter) {
    if (snapshot == null) {
      return new EncodedValues(payloads, converter);
    }
    return new CopyableEncodedValues(converter);
  }

  /**
   * Reads independent copies without exposing the snapshot used by conversion and serialization.
   */
  private final class CopyableEncodedValues extends EncodedValues {
    private DataConverter converter;

    private CopyableEncodedValues(DataConverter converter) {
      setDataConverter(converter);
    }

    @Override
    public int getSize() {
      return snapshot.size();
    }

    @Override
    public Optional<Payloads> toPayloads() {
      return ActivityInput.this.toPayloads(converter);
    }

    @Override
    public void setDataConverter(DataConverter converter) {
      this.converter = Objects.requireNonNull(converter);
    }

    @Override
    public <T> T get(int index, Class<T> type, Type genericType) {
      if (snapshot.canCopyTo(index, type, genericType)) {
        return type.cast(snapshot.copy(index));
      }
      return converter.fromPayloads(index, toPayloads(), type, genericType);
    }
  }
}
