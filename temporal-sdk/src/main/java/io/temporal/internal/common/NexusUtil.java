/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.common;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.nexusrpc.Link;
import io.temporal.api.nexus.v1.Failure;
import io.temporal.common.converter.DataConverter;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

public class NexusUtil {
  private static final JsonFormat.Printer JSON_PRINTER =
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

  public static Failure exceptionToNexusFailure(Throwable exception, DataConverter dataConverter) {
    io.temporal.api.failure.v1.Failure failure = dataConverter.exceptionToFailure(exception);
    String details;
    try {
      details = JSON_PRINTER.print(failure.toBuilder().setMessage("").build());
    } catch (InvalidProtocolBufferException e) {
      return Failure.newBuilder()
          .setMessage("Failed to serialize failure details")
          .setDetails(ByteString.copyFromUtf8(e.getMessage()))
          .build();
    }
    return Failure.newBuilder()
        .setMessage(failure.getMessage())
        .setDetails(ByteString.copyFromUtf8(details))
        .putAllMetadata(NEXUS_FAILURE_METADATA)
        .build();
  }

  private NexusUtil() {}
}
