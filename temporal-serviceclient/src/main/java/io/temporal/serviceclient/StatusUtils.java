package io.temporal.serviceclient;

import com.google.common.base.Preconditions;
import com.google.protobuf.*;
import com.google.rpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.temporal.internal.common.ProtoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StatusUtils {

  private static final Logger log = LoggerFactory.getLogger(StatusUtils.class);

  /**
   * Determines if a StatusRuntimeException contains a failure message of a given type.
   *
   * @return true if the given failure is found, false otherwise
   */
  public static boolean hasFailure(
      StatusRuntimeException exception, Class<? extends Message> failureType) {
    Preconditions.checkNotNull(exception, "exception cannot be null");
    com.google.rpc.Status status = StatusProto.fromThrowable(exception);
    if (status.getDetailsCount() == 0) {
      return false;
    }
    Any details = status.getDetails(0);
    return details.is(failureType);
  }

  /**
   * @return a failure of a given type from the StatusRuntimeException object
   */
  public static <T extends Message> T getFailure(
      StatusRuntimeException exception, Class<T> failureType) {
    Preconditions.checkNotNull(exception, "exception cannot be null");
    com.google.rpc.Status status = StatusProto.fromThrowable(exception);
    if (status.getDetailsCount() == 0) {
      return null;
    }
    Any details = status.getDetails(0);
    try {
      if (details.is(failureType)) {
        return details.unpack(failureType);
      }
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException(
          "Failure to construct gRPC error of type " + failureType + " from " + details, e);
    }
    return null;
  }

  /** Create StatusRuntimeException with given details. */
  public static <T extends Message> StatusRuntimeException newException(
      io.grpc.Status status, T details, Descriptors.Descriptor detailsDescriptor) {
    Preconditions.checkNotNull(status, "status cannot be null");
    Status protoStatus =
        Status.newBuilder()
            .setCode(status.getCode().value())
            .setMessage(status.getDescription())
            .addDetails(ProtoUtils.packAny(details, detailsDescriptor))
            .build();
    return StatusProto.toStatusRuntimeException(protoStatus);
  }
}
