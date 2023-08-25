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

/**
 * SdkFlag represents a flag used to help version the sdk internally to make breaking changes in
 * workflow logic.
 */
public enum SdkFlag {
  UNSET(0),
  /*
   * Changes behavior of GetVersion to not yield if no previous call existed in history.
   */
  SKIP_YIELD_ON_DEFAULT_VERSION(1),
  UNKNOWN(Integer.MAX_VALUE);

  private final int value;

  SdkFlag(int value) {
    this.value = value;
  }

  public boolean compare(int i) {
    return value == i;
  }

  public static SdkFlag getValue(int id) {
    SdkFlag[] as = SdkFlag.values();
    for (int i = 0; i < as.length; i++) {
      if (as[i].compare(id)) return as[i];
    }
    return SdkFlag.UNKNOWN;
  }

  public int getValue() {
    return value;
  }
}
