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

package io.temporal.common.metadata;

import java.util.*;

final class POJOReflectionUtils {
  private POJOReflectionUtils() {}

  /**
   * Return all interfaces that the given class implements as a Set, including ones implemented by
   * superclasses. This method doesn't go through interface inheritance hierarchy, meaning {@code
   * clazz} or it's superclasses need to directly implement an interface for this method to return
   * it.
   *
   * <p>If the class itself is an interface, it gets returned as sole interface.
   *
   * @param clazz the class to analyze for interfaces
   * @return all interfaces that the given object implements as a Set
   */
  public static Set<Class<?>> getTopLevelInterfaces(Class<?> clazz) {
    if (clazz.isInterface()) {
      return Collections.singleton(clazz);
    }
    Set<Class<?>> interfaces = new HashSet<>();
    Class<?> current = clazz;
    while (current != null) {
      Class<?>[] ifcs = current.getInterfaces();
      interfaces.addAll(Arrays.asList(ifcs));
      current = current.getSuperclass();
    }
    return interfaces;
  }
}
