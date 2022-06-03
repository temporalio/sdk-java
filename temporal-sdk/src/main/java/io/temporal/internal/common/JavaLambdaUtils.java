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
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class JavaLambdaUtils {

  /**
   * Get target of the method reference that was converted to a lambda.
   *
   * @param l lambda expression that could be a method reference.
   * @return either target of the method reference or null if it is not reference in form
   *     object::method.
   */
  public static Object getTarget(SerializedLambda l) {
    if (l == null) {
      return null;
    }
    // If lambda is a method call on an object then the first captured argument is the object.
    if (l.getCapturedArgCount() > 0) {
      return l.getCapturedArg(0);
    }
    return null;
  }

  /**
   * Unfortunate sorcery to reflect on lambda. Works only if function that lambda implements is
   * serializable. This is why {@link Functions} is needed as all its functions are serializable.
   *
   * @param lambda lambda that potentially implements {@link java.io.Serializable}.
   * @return lambda in {@link SerializedLambda} form or null if its function doesn't implement
   *     Serializable.
   */
  public static SerializedLambda toSerializedLambda(Object lambda) {
    for (Class<?> cl = lambda.getClass(); cl != null; cl = cl.getSuperclass()) {
      try {
        Method m = cl.getDeclaredMethod("writeReplace");
        m.setAccessible(true);
        Object replacement = m.invoke(lambda);
        if (!(replacement instanceof SerializedLambda)) break; // custom interface implementation
        return (SerializedLambda) replacement;
      } catch (NoSuchMethodException e) {
      } catch (IllegalAccessException | InvocationTargetException e) {
        break;
      }
    }
    return null;
  }

  /** Prohibits instantiation. */
  private JavaLambdaUtils() {}
}
