package io.temporal.internal.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.nexusrpc.FailureInfo;
import io.nexusrpc.Link;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.NexusHandlerErrorRetryBehavior;
import io.temporal.api.nexus.v1.Failure;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.common.converter.DataConverter;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

public class NexusUtil {
  private static final ObjectWriter JSON_OBJECT_WRITER = new ObjectMapper().writer();
  private static final JsonFormat.Printer PROTO_JSON_PRINTER =
      JsonFormat.printer().omittingInsignificantWhitespace();
  private static final String TEMPORAL_FAILURE_TYPE_STRING =
      io.temporal.api.failure.v1.Failure.getDescriptor().getFullName();
  private static final Map<String, String> NEXUS_FAILURE_METADATA =
      Collections.singletonMap("type", TEMPORAL_FAILURE_TYPE_STRING);

  public static Duration parseRequestTimeout(String timeout) {
    try {
      if (timeout.endsWith("m")) {
        return Duration.ofMillis(
            Math.round(1e3 * 60 * Double.parseDouble(timeout.substring(0, timeout.length() - 1))));
      } else if (timeout.endsWith("ms")) {
        return Duration.ofMillis(
            Math.round(Double.parseDouble(timeout.substring(0, timeout.length() - 2))));
      } else if (timeout.endsWith("s")) {
        return Duration.ofMillis(
            Math.round(1e3 * Double.parseDouble(timeout.substring(0, timeout.length() - 1))));
      } else {
        throw new IllegalArgumentException("Invalid timeout format: " + timeout);
      }
    } catch (NumberFormatException | NullPointerException e) {
      throw new IllegalArgumentException("Invalid timeout format: " + timeout);
    }
  }

  public static Link nexusProtoLinkToLink(io.temporal.api.nexus.v1.Link nexusLink)
      throws URISyntaxException {
    return Link.newBuilder()
        .setType(nexusLink.getType())
        .setUri(new URI(nexusLink.getUrl()))
        .build();
  }

  public static Failure temporalFailureToNexusFailure(
      io.temporal.api.failure.v1.Failure temporalFailure) {
    String details;
    try {
      details = PROTO_JSON_PRINTER.print(temporalFailure.toBuilder().setMessage("").build());
    } catch (InvalidProtocolBufferException e) {
      return Failure.newBuilder()
          .setMessage("Failed to serialize failure details")
          .setDetails(ByteString.copyFromUtf8(e.getMessage()))
          .build();
    }
    return Failure.newBuilder()
        .setMessage(temporalFailure.getMessage())
        .setDetails(ByteString.copyFromUtf8(details))
        .putAllMetadata(NEXUS_FAILURE_METADATA)
        .build();
  }

  public static io.temporal.api.failure.v1.Failure nexusFailureToAPIFailure(
      FailureInfo failureInfo, boolean retryable) {
    io.temporal.api.failure.v1.Failure.Builder apiFailure =
        io.temporal.api.failure.v1.Failure.newBuilder();

    if (failureInfo.getMetadata().containsKey("type")
        && failureInfo.getMetadata().get("type").equals(TEMPORAL_FAILURE_TYPE_STRING)) {
      // Details contains a JSON-serialized Temporal failure
      try {
        JsonFormat.parser().ignoringUnknownFields().merge(failureInfo.getDetailsJson(), apiFailure);
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }
    } else {
      // Create an ApplicationFailure with the Nexus failure data
      io.temporal.api.common.v1.Payloads payloads = nexusFailureMetadataToPayloads(failureInfo);
      io.temporal.api.failure.v1.ApplicationFailureInfo.Builder appFailureInfo =
          io.temporal.api.failure.v1.ApplicationFailureInfo.newBuilder()
              .setType("NexusFailure")
              .setNonRetryable(!retryable);
      if (payloads != null) {
        appFailureInfo.setDetails(payloads);
      }
      apiFailure.setApplicationFailureInfo(appFailureInfo.build());
    }

    // Ensure these always get written
    apiFailure.setMessage(failureInfo.getMessage());
    if (!failureInfo.getStackTrace().isEmpty()) {
      apiFailure.setStackTrace(failureInfo.getStackTrace());
    }

    return apiFailure.build();
  }

  private static io.temporal.api.common.v1.Payloads nexusFailureMetadataToPayloads(
      FailureInfo failureInfo) {
    if (failureInfo.getMetadata().isEmpty() && failureInfo.getDetailsJson().isEmpty()) {
      return null;
    }

    // Create a copy without the message before serializing
    FailureInfo failureCopy = FailureInfo.newBuilder(failureInfo).setMessage("").build();
    String json = null;
    try {
      json = JSON_OBJECT_WRITER.writeValueAsString(failureCopy);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }

    return io.temporal.api.common.v1.Payloads.newBuilder()
        .addPayloads(
            Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                .setData(ByteString.copyFromUtf8(json))
                .build())
        .build();
  }

  public static FailureInfo temporalFailureToNexusFailureInfo(
      io.temporal.api.failure.v1.Failure temporalFailure) {
    String details;
    try {
      details = PROTO_JSON_PRINTER.print(temporalFailure.toBuilder().setMessage("").build());
    } catch (InvalidProtocolBufferException e) {
      return FailureInfo.newBuilder()
          .setMessage("Failed to serialize failure details")
          .setDetailsJson(e.getMessage())
          .build();
    }
    return FailureInfo.newBuilder()
        .setMessage(temporalFailure.getMessage())
        .setDetailsJson(details)
        .putMetadata("type", TEMPORAL_FAILURE_TYPE_STRING)
        .build();
  }

  public static Failure exceptionToNexusFailure(Throwable exception, DataConverter dataConverter) {
    io.temporal.api.failure.v1.Failure failure = dataConverter.exceptionToFailure(exception);
    return temporalFailureToNexusFailure(failure);
  }

  public static HandlerError handlerErrorToNexusError(
      HandlerException e, DataConverter dataConverter) {
    HandlerError.Builder handlerError =
        HandlerError.newBuilder()
            .setErrorType(e.getErrorType().toString())
            .setRetryBehavior(mapRetryBehavior(e.getRetryBehavior()));
    // TODO: check if this works on old server
    if (e.getCause() != null) {
      handlerError.setFailure(exceptionToNexusFailure(e.getCause(), dataConverter));
    }
    return handlerError.build();
  }

  private static NexusHandlerErrorRetryBehavior mapRetryBehavior(
      HandlerException.RetryBehavior retryBehavior) {
    switch (retryBehavior) {
      case RETRYABLE:
        return NexusHandlerErrorRetryBehavior.NEXUS_HANDLER_ERROR_RETRY_BEHAVIOR_RETRYABLE;
      case NON_RETRYABLE:
        return NexusHandlerErrorRetryBehavior.NEXUS_HANDLER_ERROR_RETRY_BEHAVIOR_NON_RETRYABLE;
      default:
        return NexusHandlerErrorRetryBehavior.NEXUS_HANDLER_ERROR_RETRY_BEHAVIOR_UNSPECIFIED;
    }
  }

  private NexusUtil() {}
}
