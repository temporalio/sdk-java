package io.temporal.internal.common;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.NexusHandlerFailureInfo;
import io.temporal.api.nexus.v1.HandlerError;
import java.util.Map;
import java.util.stream.Collectors;

public class NexusFailureUtil {
  private static final JsonFormat.Parser JSON_PARSER = JsonFormat.parser();
  private static final String FAILURE_TYPE_STRING = Failure.getDescriptor().getFullName();

  public static Failure handlerErrorToFailure(HandlerError err) {
    return Failure.newBuilder()
        .setMessage(err.getFailure().getMessage())
        .setNexusHandlerFailureInfo(
            NexusHandlerFailureInfo.newBuilder()
                .setType(err.getErrorType())
                .setRetryBehavior(err.getRetryBehavior())
                .build())
        .setCause(nexusFailureToAPIFailure(err.getFailure(), false))
        .build();
  }

  /**
   * nexusFailureToAPIFailure converts a Nexus Failure to an API proto Failure. If the failure
   * metadata "type" field is set to the fullname of the temporal API Failure message, the failure
   * is reconstructed using protojson.Unmarshal on the failure details field.
   */
  public static Failure nexusFailureToAPIFailure(
      io.temporal.api.nexus.v1.Failure failure, boolean retryable) {
    Failure.Builder apiFailure = Failure.newBuilder();
    if (failure.getMetadataMap().containsKey("type")
        && failure.getMetadataMap().get("type").equals(FAILURE_TYPE_STRING)) {
      try {
        JSON_PARSER.merge(failure.getDetails().toString(UTF_8), apiFailure);
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }
    } else {
      Payloads payloads = nexusFailureMetadataToPayloads(failure);
      ApplicationFailureInfo.Builder applicationFailureInfo = ApplicationFailureInfo.newBuilder();
      applicationFailureInfo.setType("NexusFailure");
      applicationFailureInfo.setDetails(payloads);
      applicationFailureInfo.setNonRetryable(!retryable);
      apiFailure.setApplicationFailureInfo(applicationFailureInfo.build());
    }
    apiFailure.setMessage(failure.getMessage());
    return apiFailure.build();
  }

  public static Payloads nexusFailureMetadataToPayloads(io.temporal.api.nexus.v1.Failure failure) {
    Map<String, ByteString> metadata =
        failure.getMetadataMap().entrySet().stream()
            .collect(
                Collectors.toMap(Map.Entry::getKey, e -> ByteString.copyFromUtf8(e.getValue())));
    return Payloads.newBuilder()
        .addPayloads(Payload.newBuilder().putAllMetadata(metadata).setData(failure.getDetails()))
        .build();
  }
}
