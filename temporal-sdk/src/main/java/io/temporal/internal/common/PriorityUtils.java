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
