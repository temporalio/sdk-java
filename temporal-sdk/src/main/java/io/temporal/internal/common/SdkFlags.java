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

import io.temporal.workflow.Functions;
import java.util.EnumSet;

/** Represents all the flags that are currently set in a workflow execution. */
public final class SdkFlags {
  private final boolean supportSdkMetadata;
  private final Functions.Func<Boolean> replaying;
  // Flags that have been received from the server or have not been sent yet.
  private final EnumSet<SdkFlag> sdkFlags = EnumSet.noneOf(SdkFlag.class);
  // Flags that have been set this WFT that have not been sent to the server.
  // Keep track of them separately, so we know what to send to the server.
  private final EnumSet<SdkFlag> unsentSdkFlags = EnumSet.noneOf(SdkFlag.class);

  public SdkFlags(boolean supportSdkMetadata, Functions.Func<Boolean> replaying) {
    this.supportSdkMetadata = supportSdkMetadata;
    this.replaying = replaying;
  }

  /**
   * Marks a flag as usable regardless of replay status.
   *
   * @return True, as long as the server supports SDK flags
   */
  public boolean setSdkFlag(SdkFlag flag) {
    if (!supportSdkMetadata) {
      return false;
    }
    sdkFlags.add(flag);
    return true;
  }

  /**
   * @return True if this flag may currently be used.
   */
  public boolean tryUseSdkFlag(SdkFlag flag) {
    if (!supportSdkMetadata) {
      return false;
    }

    if (!replaying.apply()) {
      sdkFlags.add(flag);
      unsentSdkFlags.add(flag);
      return true;
    } else {
      return sdkFlags.contains(flag);
    }
  }

  /**
   * @return All flags set since the last call to takeNewSdkFlags.
   */
  public EnumSet<SdkFlag> takeNewSdkFlags() {
    EnumSet<SdkFlag> result = unsentSdkFlags.clone();
    unsentSdkFlags.clear();
    return result;
  }
}
