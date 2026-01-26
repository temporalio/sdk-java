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

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.slf4j.Logger;

/** Internal utilities for plugin management. */
public final class PluginUtils {

  private PluginUtils() {}

  /**
   * Merges propagated plugins with explicitly specified plugins. Propagated plugins come first,
   * followed by explicit plugins. Warns about duplicate plugin instances.
   *
   * @param propagated plugins propagated from a parent component (may be null or empty)
   * @param explicit plugins explicitly specified on this component (may be null or empty)
   * @param nameExtractor function to extract plugin name for logging
   * @param log logger for duplicate warnings
   * @param propagatedSource description of where propagated plugins come from (e.g., "service
   *     stubs", "client")
   * @param <T> the plugin type
   * @return merged array of plugins, never null
   */
  public static <T> T[] mergePlugins(
      @Nullable T[] propagated,
      @Nullable T[] explicit,
      Function<T, String> nameExtractor,
      Logger log,
      String propagatedSource,
      Class<T> pluginClass) {
    boolean propagatedEmpty = propagated == null || propagated.length == 0;
    boolean explicitEmpty = explicit == null || explicit.length == 0;

    if (propagatedEmpty && explicitEmpty) {
      @SuppressWarnings("unchecked")
      T[] empty = (T[]) Array.newInstance(pluginClass, 0);
      return empty;
    }
    if (propagatedEmpty) {
      return explicit;
    }
    if (explicitEmpty) {
      return propagated;
    }

    // Warn about duplicate plugin instances (same object in both lists)
    Set<T> propagatedSet = new HashSet<>(Arrays.asList(propagated));
    for (T p : explicit) {
      if (propagatedSet.contains(p)) {
        log.warn(
            "Plugin instance {} is present in both propagated plugins (from {}) and "
                + "explicit plugins. It will run twice which may not be the intended behavior.",
            nameExtractor.apply(p),
            propagatedSource);
      }
    }

    @SuppressWarnings("unchecked")
    T[] merged = (T[]) Array.newInstance(pluginClass, propagated.length + explicit.length);
    System.arraycopy(propagated, 0, merged, 0, propagated.length);
    System.arraycopy(explicit, 0, merged, propagated.length, explicit.length);
    return merged;
  }
}
