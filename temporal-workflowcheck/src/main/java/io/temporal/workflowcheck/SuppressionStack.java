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
