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

package io.temporal.workflowcheck;

import java.util.Deque;
import java.util.LinkedList;
import javax.annotation.Nullable;

/** Utility to push/pop configured suppressions. */
class SuppressionStack {
  // If a value is null, that means suppress all
  private final Deque<DescriptorMatcher> stack = new LinkedList<>();

  // If null or empty string array given, all things suppressed
  void push(@Nullable String[] specificDescriptors) {
    if (specificDescriptors == null || specificDescriptors.length == 0) {
      stack.push(null);
    } else {
      stack.push(new DescriptorMatcher(specificDescriptors));
    }
  }

  void pop() {
    stack.pop();
  }

  boolean checkSuppressed(String className, String methodName, String methodDescriptor) {
    // Since suppressions are only additive, we can iterate in any order we want
    for (DescriptorMatcher matcher : stack) {
      // If matcher is null, that means suppress all
      if (matcher == null) {
        return true;
      }
      Boolean suppressed = matcher.check(className, methodName, methodDescriptor);
      if (suppressed != null && suppressed) {
        return true;
      }
    }
    return false;
  }
}
