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

package io.temporal.internal.common.kotlin;

import java.lang.annotation.Annotation;

/** This class allows checking if the class is Kotlin class without using any Kotlin dependencies */
@SuppressWarnings("unchecked")
public abstract class KotlinDetector {

  private static final Class<? extends Annotation> kotlinMetadata;

  private static final boolean kotlinReflectPresent;

  static {
    Class<?> metadata;
    ClassLoader classLoader = KotlinDetector.class.getClassLoader();
    try {
      metadata = Class.forName("kotlin.Metadata", false, classLoader);
    } catch (ClassNotFoundException ex) {
      // Kotlin API not available - no Kotlin support
      metadata = null;
    }
    kotlinMetadata = (Class<? extends Annotation>) metadata;
    kotlinReflectPresent = isPresent("kotlin.reflect.full.KClasses", classLoader);
  }

  public static boolean isPresent(String className, ClassLoader classLoader) {
    try {
      Class.forName(className, false, classLoader);
      return true;
    } catch (IllegalAccessError err) {
      throw new IllegalStateException(
          "Readability mismatch in inheritance hierarchy of class ["
              + className
              + "]: "
              + err.getMessage(),
          err);
    } catch (Throwable ex) {
      // Typically ClassNotFoundException or NoClassDefFoundError...
      return false;
    }
  }

  /** Determine whether Kotlin is present in general. */
  public static boolean isKotlinPresent() {
    return (kotlinMetadata != null);
  }

  /** Determine whether Kotlin reflection is present. */
  public static boolean isKotlinReflectPresent() {
    return kotlinReflectPresent;
  }

  /**
   * Determine whether the given {@code Class} is a Kotlin type (with Kotlin metadata present on
   * it).
   */
  public static boolean isKotlinType(Class<?> clazz) {
    return (kotlinMetadata != null && clazz.getDeclaredAnnotation(kotlinMetadata) != null);
  }
}
