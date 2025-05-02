package io.temporal.internal.common;

import io.temporal.api.common.v1.Priority;
import javax.annotation.Nonnull;

public class PriorityUtils {

  /** Convert to gRPC representation. */
  public static Priority toProto(io.temporal.common.Priority priority) {
    return Priority.newBuilder().setPriorityKey(priority.getPriorityKey()).build();
  }

  /** Convert from gRPC representation. */
  @Nonnull
  public static io.temporal.common.Priority fromProto(@Nonnull Priority priority) {
    return io.temporal.common.Priority.newBuilder()
        .setPriorityKey(priority.getPriorityKey())
        .build();
  }

  private PriorityUtils() {}
}
