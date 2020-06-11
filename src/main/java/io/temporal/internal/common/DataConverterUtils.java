/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.common;

import io.temporal.proto.common.Header;
import io.temporal.proto.common.Payload;
import java.lang.reflect.Field;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataConverterUtils {
  private static final Logger log = LoggerFactory.getLogger(DataConverterUtils.class);

  private static final boolean SETTING_PRIVATE_FIELD_ALLOWED;

  static {
    boolean value = false;
    try {
      Field causeField = Throwable.class.getDeclaredField("cause");
      causeField.setAccessible(true);
      causeField.set(new RuntimeException(), null);
      value = true;
    } catch (IllegalAccessException e) {
    } catch (Exception ex) {
      throw new Error("Unexpected", ex);
    }
    SETTING_PRIVATE_FIELD_ALLOWED = value;
  }

  /** Are JVM permissions allowing setting private fields using reflection? */
  public static boolean isSettingPrivateFieldAllowed() {
    return SETTING_PRIVATE_FIELD_ALLOWED;
  }

  /**
   * We want to serialize the throwable and its cause separately, so that if the throwable is
   * serializable but the cause is not, we can still serialize them correctly (i.e. we serialize the
   * throwable correctly and convert the cause to a data converter exception). If existing cause is
   * not detached due to security policy then null is returned.
   */
  public static Throwable detachCause(Throwable throwable) {
    Throwable cause = null;
    if (isSettingPrivateFieldAllowed()
        && throwable.getCause() != null
        && throwable.getCause() != throwable) {
      try {
        cause = throwable.getCause();
        Field causeField = Throwable.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(throwable, null);
      } catch (Exception e) {
        log.warn("Failed to clear cause in original throwable.", e);
      }
    }
    return cause;
  }

  public static Header toHeaderGrpc(Map<String, Payload> headers) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    Header.Builder builder = Header.newBuilder();
    for (Map.Entry<String, Payload> item : headers.entrySet()) {
      builder.putFields(item.getKey(), item.getValue());
    }
    return builder.build();
  }

  private DataConverterUtils() {}
}
