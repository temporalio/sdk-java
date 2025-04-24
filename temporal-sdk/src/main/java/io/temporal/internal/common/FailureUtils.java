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

import io.temporal.api.enums.v1.ApplicationErrorCategory;
import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.worker.WorkflowExecutionException;
import javax.annotation.Nullable;

public class FailureUtils {
  public static boolean isBenignApplicationFailure(@Nullable Throwable t) {
    if (t == null) {
      return false;
    }

    if (t instanceof ApplicationFailure
        && ((ApplicationFailure) t).getApplicationErrorCategory()
            == ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN) {
      return true;
    }

    // Handle WorkflowExecutionException, which wraps a protobuf Failure
    if (t instanceof WorkflowExecutionException) {
      Failure failure = ((WorkflowExecutionException) t).getFailure();
      if (failure.hasApplicationFailureInfo()
          && failure.getApplicationFailureInfo().getCategory()
              == ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN) {
        return true;
      }
    }

    // Handle ActivityFailure, which wraps the actual ApplicationFailure
    if (t instanceof ActivityFailure) {
      Throwable cause = t.getCause();
      boolean result = cause != null && isBenignApplicationFailure(cause);
      return result;
    }

    // Check the immediate cause.
    Throwable cause = t.getCause();
    boolean result =
        cause != null
            && cause instanceof ApplicationFailure
            && ((ApplicationFailure) cause).getApplicationErrorCategory()
                == ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN;
    return result;
  }
}
