/*
 * Copyright (C) 2024 Temporal Technologies, Inc. All Rights Reserved.
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

package io.temporal.failure;

/**
 * Mirrors the proto definition for ApplicationErrorCategory. Used to categorize application
 * failures.
 *
 * @see io.temporal.api.enums.v1.ApplicationErrorCategory
 */
public enum ApplicationErrorCategory {
  UNSPECIFIED,
  /** Expected application error with little/no severity. */
  BENIGN,
  ;

  public static ApplicationErrorCategory fromProto(
      io.temporal.api.enums.v1.ApplicationErrorCategory protoCategory) {
    if (protoCategory == null) {
      return UNSPECIFIED;
    }
    switch (protoCategory) {
      case APPLICATION_ERROR_CATEGORY_BENIGN:
        return BENIGN;
      case APPLICATION_ERROR_CATEGORY_UNSPECIFIED:
      case UNRECOGNIZED:
      default:
        // Fallback unrecognized or unspecified proto values as UNSPECIFIED
        return UNSPECIFIED;
    }
  }

  public io.temporal.api.enums.v1.ApplicationErrorCategory toProto() {
    switch (this) {
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
