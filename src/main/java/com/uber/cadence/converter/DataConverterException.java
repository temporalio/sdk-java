/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.converter;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * @author fateev
 * @see DataConverter
 */
@SuppressWarnings("serial")
public class DataConverterException extends RuntimeException {

  public DataConverterException(byte[] content, Type[] valueTypes, Throwable cause) {
    super(toMessage(null, content, valueTypes), cause);
  }

  public DataConverterException(Throwable cause) {
    super(cause);
  }

  public DataConverterException(String message, Throwable cause) {
    super(message, cause);
  }

  public DataConverterException(String message, byte[] content, Type[] valueTypes) {
    super(toMessage(message, content, valueTypes));
  }

  private static String toMessage(String message, byte[] content, Type[] valueTypes) {
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

  private static String truncateContent(byte[] content) {
    if (content == null) {
      return "";
    }
    // Limit size of the string.
    int maxIndex = Math.min(content.length, 255);
    return new String(content, 0, maxIndex, StandardCharsets.UTF_8);
  }
}
