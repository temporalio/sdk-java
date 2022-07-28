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

package io.temporal.common.converter;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * @author fateev
 * @see DataConverter
 */
public class DataConverterException extends RuntimeException {

  /** Maximum size of data to be included into the message. Used to avoid very large payloads. */
  public static final int MESSAGE_TRUNCATION_SIZE = 255;

  public DataConverterException(String message) {
    super(message);
  }

  public DataConverterException(Throwable cause) {
    super(cause);
  }

  public DataConverterException(String message, Throwable cause) {
    super(message, cause);
  }

  public DataConverterException(Payload content, Type[] valueTypes, Throwable cause) {
    super(toMessageDeserializing(null, content, valueTypes), cause);
  }

  public DataConverterException(String message, Payload content, Type[] valueTypes) {
    super(toMessageDeserializing(message, content, valueTypes));
  }

  public DataConverterException(String message, Optional<Payloads> content, Type valueType) {
    super(toMessageDeserializing(message, content, valueType));
  }

  public DataConverterException(String message, Optional<Payloads> content, Type[] valueTypes) {
    super(toMessageDeserializing(message, content, valueTypes));
  }

  public <T> DataConverterException(Payload payload, Class<T> valueClass, Throwable e) {
    super(toMessageDeserializing(e.getMessage(), payload, new Type[] {valueClass}), e);
  }

  private static String toMessageDeserializing(
      String message, Optional<Payloads> content, Type[] valueTypes) {
    if (content == null && valueTypes == null || valueTypes.length == 0) {
      return message;
    }
    StringBuilder result = new StringBuilder();
    if (message != null && message.length() > 0) {
      result.append(message);
      result.append(" ");
    }
    result.append("when parsing:\"");
    result.append(truncateContent(content));
    result.append("\" into following types: ");
    result.append(Arrays.toString(valueTypes));
    return result.toString();
  }

  private static String toMessageDeserializing(
      String message, Optional<Payloads> content, Type valueType) {
    if (!content.isPresent() && valueType == null) {
      return message;
    }
    StringBuilder result = new StringBuilder();
    if (message != null && message.length() > 0) {
      result.append(message);
      result.append(" ");
    }
    result.append("when parsing:\"");
    result.append(truncateContent(content));
    result.append("\" into following types: ");
    result.append(valueType);
    return result.toString();
  }

  private static String toMessageDeserializing(String message, Payload content, Type[] valueTypes) {
    if (content == null && valueTypes == null) {
      return message;
    }
    StringBuilder result = new StringBuilder();
    if (message != null && message.length() > 0) {
      result.append(message);
      result.append(" ");
    }
    result.append("when parsing:\"");
    result.append(truncateContent(content));
    result.append("\" into following types: ");
    result.append(Arrays.toString(valueTypes));
    return result.toString();
  }

  private static String truncateContent(Optional<Payloads> content) {
    if (!content.isPresent()) {
      return "<EMPTY PAYLOAD>";
    }
    // Limit size of the string.
    String data;
    if (content.get().getPayloadsCount() == 1) {
      data = content.get().getPayloads(0).getData().toString(StandardCharsets.UTF_8);
    } else {
      data = String.valueOf(content);
    }
    int maxIndex = Math.min(data.length(), MESSAGE_TRUNCATION_SIZE);
    return data.substring(0, maxIndex);
  }

  private static String truncateContent(Payload content) {
    if (content == null) {
      return "";
    }
    // Limit size of the string.
    String data = content.getData().toString(StandardCharsets.UTF_8);
    int maxIndex = Math.min(data.length(), MESSAGE_TRUNCATION_SIZE);
    return data.substring(0, maxIndex);
  }
}
