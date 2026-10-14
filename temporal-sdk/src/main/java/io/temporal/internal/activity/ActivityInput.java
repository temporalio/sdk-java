package io.temporal.internal.activity;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nullable;

/** One source of activity arguments: serialized payloads or a captured copyable snapshot. */
final class ActivityInput {
  private final Optional<Payloads> payloads;
  private final @Nullable LocalActivityArguments snapshot;

  ActivityInput(
      PollActivityTaskQueueResponseOrBuilder response, @Nullable LocalActivityArguments snapshot) {
    this.snapshot = snapshot;
    this.payloads =
        snapshot == null && response.hasInput()
            ? Optional.of(response.getInput())
            : Optional.empty();
  }

  Object[] getArguments(Class<?>[] types, Type[] genericTypes, DataConverter converter) {
    if (snapshot != null && snapshot.canCopyTo(types)) {
      return snapshot.copy();
    }
    return converter.fromPayloads(
        snapshot == null ? payloads : snapshot.toPayloads(converter), types, genericTypes);
  }

  EncodedValues getEncodedValues(DataConverter converter) {
    if (snapshot == null) {
      return new EncodedValues(payloads, converter);
    }
    EncodedValues values = new EncodedValues(snapshot.copy());
    values.setDataConverter(converter);
    return values;
  }
}
