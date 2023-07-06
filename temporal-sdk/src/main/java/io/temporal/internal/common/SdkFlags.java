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

import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.workflow.Functions;
import java.util.ArrayList;
import java.util.List;

/** Represents all the flags that are currently set in a workflow execution. */
public final class SdkFlags {
  private final GetSystemInfoResponse.Capabilities capabilities;
  private final Functions.Func<Boolean> replaying;
  // Flags that have been received from the server
  private final List<SdkFlag> sdkFlags = new ArrayList<>();
  // Flags that have been set this WFT that have not been sent to the server.
  // Keep track of them separately, so we know what to send to the server.
  private final List<SdkFlag> unsentSdkFlags = new ArrayList<>();

  public SdkFlags(
      GetSystemInfoResponse.Capabilities capabilities, Functions.Func<Boolean> replaying) {
    this.capabilities = capabilities;
    this.replaying = replaying;
  }

  /**
   * Marks a flag as usable regardless of replay status.
   *
   * @return True, as long as the server supports SDK flags
   */
  public boolean setSdkFlag(SdkFlag flag) {
    if (!capabilities.getSdkMetadata()) {
      return false;
    }
    sdkFlags.add(flag);
    return true;
  }

  /**
   * @return True if this flag may currently be used.
   */
  public boolean tryUseSdkFlag(SdkFlag flag) {
    if (!capabilities.getSdkMetadata()) {
      return false;
    }

    if (!replaying.apply()) {
      unsentSdkFlags.add(flag);
      return true;
    } else {
      return unsentSdkFlags.contains(flag) || sdkFlags.contains(flag);
    }
  }

  /***
   * @return All flags set since the last call to takeNewSdkFlags.
   */
  public List<Integer> takeNewSdkFlags() {
    List<Integer> result = new ArrayList<>(unsentSdkFlags.size());
    for (SdkFlag flag : unsentSdkFlags) {
      sdkFlags.add(flag);
      result.add(flag.getValue());
    }
    unsentSdkFlags.clear();
    return result;
  }
}
