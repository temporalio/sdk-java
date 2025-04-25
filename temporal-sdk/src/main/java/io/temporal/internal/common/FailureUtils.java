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

import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.ApplicationErrorCategory;
import io.temporal.failure.ApplicationFailure;
import javax.annotation.Nullable;

public class FailureUtils {
  private FailureUtils() {}

  public static boolean isBenignApplicationFailure(@Nullable Throwable t) {
    if (t instanceof ApplicationFailure
        && ((ApplicationFailure) t).getApplicationErrorCategory()
            == ApplicationErrorCategory.BENIGN) {
      return true;
    }
    return false;
  }

  public static boolean isBenignApplicationFailure(@Nullable Failure failure) {
    if (failure != null
        && failure.getApplicationFailureInfo() != null
        && FailureUtils.categoryFromProto(failure.getApplicationFailureInfo().getCategory())
            == ApplicationErrorCategory.BENIGN) {
      return true;
    }
    return false;
  }

  public static ApplicationErrorCategory categoryFromProto(
      io.temporal.api.enums.v1.ApplicationErrorCategory protoCategory) {
    if (protoCategory == null) {
      return ApplicationErrorCategory.UNSPECIFIED;
    }
    switch (protoCategory) {
      case APPLICATION_ERROR_CATEGORY_BENIGN:
        return ApplicationErrorCategory.BENIGN;
      case APPLICATION_ERROR_CATEGORY_UNSPECIFIED:
      case UNRECOGNIZED:
      default:
        // Fallback unrecognized or unspecified proto values as UNSPECIFIED
        return ApplicationErrorCategory.UNSPECIFIED;
    }
  }

  public static io.temporal.api.enums.v1.ApplicationErrorCategory categoryToProto(
      io.temporal.failure.ApplicationErrorCategory category) {
    switch (category) {
      case BENIGN:
        return io.temporal.api.enums.v1.ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN;
      case UNSPECIFIED:
      default:
        // Fallback to UNSPECIFIED for unknown values
        return io.temporal.api.enums.v1.ApplicationErrorCategory
            .APPLICATION_ERROR_CATEGORY_UNSPECIFIED;
    }
  }
}
