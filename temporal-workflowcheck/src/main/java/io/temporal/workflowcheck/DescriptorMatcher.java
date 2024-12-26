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

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;

/**
 * Matcher for a set of descriptors. Pattern is <code>
 * [[qualified/class/]Name.]memberName[(Lthe/Method/Desc;)V]</code>.
 */
class DescriptorMatcher {
  private final Map<String, Boolean> descriptors;

  DescriptorMatcher(Map<String, Boolean> descriptors) {
    this.descriptors = descriptors;
  }

  DescriptorMatcher(String category, Properties[] propSets) {
    this(new HashMap<>());
    for (Properties props : propSets) {
      addFromProperties(category, props);
    }
  }

  DescriptorMatcher(String[] positiveMatches) {
    this(new HashMap<>(positiveMatches.length));
    for (String positiveMatch : positiveMatches) {
      descriptors.put(positiveMatch, true);
    }
  }

  void addFromProperties(String category, Properties props) {
    String prefix = "temporal.workflowcheck." + category + ".";
    for (Map.Entry<Object, Object> entry : props.entrySet()) {
      // Key is temporal.workflowcheck.<category>.<pattern>=<true|false>
      String key = (String) entry.getKey();
      if (!key.startsWith(prefix)) {
        continue;
      }
      // Sanity check to confirm methods with descriptors need to _not_ have
      // return values
      int closeParenIndex = key.lastIndexOf(')');
      if (closeParenIndex > 0 && closeParenIndex != key.length() - 1) {
        throw new IllegalArgumentException(
            "Config key '" + key + "' should not have anything after ')'");
      }
      String desc = key.substring(31);
      String value = (String) entry.getValue();
      if ("true".equals(value)) {
        descriptors.put(desc, true);
      } else if ("false".equals(value)) {
        descriptors.put(desc, false);
      } else {
        throw new IllegalArgumentException(
            "Config key " + key + " supposed to be true or false, was " + value);
      }
    }
  }

  @Nullable
  Boolean check(String className, @Nullable String memberName, @Nullable String methodDescriptor) {
    // Check full descriptor sans return, then full sans params, then just
    // member, then just member sans params, then FQCN, and then each parent
    // package. We remove return values from the method descriptor since the
    // map only allows arguments.
    if (methodDescriptor != null) {
      methodDescriptor = methodDescriptor.substring(0, methodDescriptor.indexOf(')') + 1);
    }

    // Member name + descriptor doesn't have to be present to check class
    if (memberName != null) {
      // Try qualified class with method
      String classAndMember = className + "." + memberName;
      if (methodDescriptor != null) {
        Boolean invalid = descriptors.get(classAndMember + methodDescriptor);
        if (invalid != null) {
          return invalid;
        }
      }
      Boolean invalid = descriptors.get(classAndMember);
      if (invalid != null) {
        return invalid;
      }
      // Try unqualified class with member
      int slashIndex = className.lastIndexOf('/');
      if (slashIndex > 0) {
        classAndMember = classAndMember.substring(slashIndex + 1);
        if (methodDescriptor != null) {
          invalid = descriptors.get(classAndMember + methodDescriptor);
          if (invalid != null) {
            return invalid;
          }
        }
        invalid = descriptors.get(classAndMember);
        if (invalid != null) {
          return invalid;
        }
      }
      // Just member
      if (methodDescriptor != null) {
        invalid = descriptors.get(memberName + methodDescriptor);
        if (invalid != null) {
          return invalid;
        }
      }
      invalid = descriptors.get(memberName);
      if (invalid != null) {
        return invalid;
      }
    }
    // Unqualified class name
    int slashIndex = className.lastIndexOf('/');
    if (slashIndex > 0) {
      Boolean invalid = descriptors.get(className.substring(slashIndex + 1));
      if (invalid != null) {
        return invalid;
      }
    }
    // All packages above class
    while (true) {
      Boolean invalid = descriptors.get(className);
      if (invalid != null) {
        return invalid;
      }
      int slash = className.lastIndexOf('/');
      if (slash == -1) {
        return null;
      }
      className = className.substring(0, slash);
    }
  }
}
