package io.temporal.internal.statemachines;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import java.util.Optional;

public interface UpdateProtocolCallback {
  void accept();

  void reject(Failure failure);

  void complete(Optional<Payloads> result, Failure failure);

  boolean isReplaying();
}
