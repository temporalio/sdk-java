package io.temporal.internal.common;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

public class GrpcUtils {
  /**
   * @return true if {@code ex} is a gRPC exception about a channel shutdown
   */
  public static boolean isChannelShutdownException(StatusRuntimeException ex) {
    String description = ex.getStatus().getDescription();
    return (Status.Code.UNAVAILABLE.equals(ex.getStatus().getCode())
        && description != null
        && (description.startsWith("Channel shutdown")
            || description.startsWith("Subchannel shutdown")));
  }
}
