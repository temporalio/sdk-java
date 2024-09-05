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

package io.temporal.internal.testservice;

import static io.temporal.common.converter.EncodingKeys.METADATA_ENCODING_KEY;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

class StateUtils {
  private static Payload nullPayload = DefaultDataConverter.STANDARD_INSTANCE.toPayload(null).get();
  private static Payload emptyListPayload =
      DefaultDataConverter.STANDARD_INSTANCE.toPayload(new String[] {}).get();
  /**
   * @return true if the workflow was completed not by workflow task completion result
   */
  public static boolean isWorkflowExecutionForcefullyCompleted(StateMachines.State state) {
    switch (state) {
      case TERMINATED:
      case TIMED_OUT:
        return true;
      default:
        return false;
    }
  }

  private static boolean isEqual(Payload a, Payload b) {
    String aEnc = a.getMetadataOrDefault(METADATA_ENCODING_KEY, ByteString.EMPTY).toStringUtf8();
    String bEnc = b.getMetadataOrDefault(METADATA_ENCODING_KEY, ByteString.EMPTY).toStringUtf8();
    return aEnc.equals(bEnc) && a.getData().equals(b.getData());
  }

  public static @Nonnull Map<String, Payload> mergeMemo(
      @Nonnull Map<String, Payload> src, @Nonnull Map<String, Payload> dst) {
    HashMap result = new HashMap(src);
    dst.forEach(
        (k, v) -> {
          // Remove the key if the value is null or encoding is binary/null
          if (v == null || isEqual(v, nullPayload) || isEqual(v, emptyListPayload)) {
            result.remove(k);
            return;
          }
          result.put(k, v);
        });
    return result;
  }
}
