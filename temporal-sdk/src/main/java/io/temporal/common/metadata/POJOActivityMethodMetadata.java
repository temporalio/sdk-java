/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.common.metadata;

import com.google.common.base.Strings;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.lang.reflect.Method;
import java.util.Objects;

/** Metadata about a single activity method. */
public final class POJOActivityMethodMetadata {
  private final boolean hasActivityMethodAnnotation;
  private final String name;
  private final Method method;
  private final Class<?> interfaceType;

  POJOActivityMethodMetadata(
      Method method, Class<?> interfaceType, ActivityInterface activityAnnotation) {
    this.method = Objects.requireNonNull(method);
    this.interfaceType = Objects.requireNonNull(interfaceType);
    ActivityMethod activityMethod = method.getAnnotation(ActivityMethod.class);
    String name;
    if (activityMethod != null && !activityMethod.name().isEmpty()) {
      hasActivityMethodAnnotation = true;
      name = activityMethod.name();
    } else {
      hasActivityMethodAnnotation = false;
      name = activityAnnotation.namePrefix() + getActivityNameFromMethod(method);
    }
    this.name = name;
  }

  // Capitalize the first letter
  // TODO(maxim): make activity name generation pluggable through options
  private static String getActivityNameFromMethod(Method method) {
    String name = method.getName();
    return name.substring(0, 1).toUpperCase() + name.substring(1);
  }

  /** Name of activity type that this method implements */
  public String getActivityTypeName() {
    if (Strings.isNullOrEmpty(name)) {
      throw new IllegalStateException("Not annotated: " + method);
    }
    return name;
  }

  /** Method that implements the activity. */
  public Method getMethod() {
    return method;
  }

  public Class<?> getInterfaceType() {
    return interfaceType;
  }

  /** Compare and hash based on method and the interface type only. */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    POJOActivityMethodMetadata that = (POJOActivityMethodMetadata) o;
    return com.google.common.base.Objects.equal(method, that.method)
        && com.google.common.base.Objects.equal(interfaceType, that.interfaceType);
  }

  /** Compare and hash based on method and the interface type only. */
  @Override
  public int hashCode() {
    return com.google.common.base.Objects.hashCode(method, interfaceType);
  }
}
