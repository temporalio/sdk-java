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

package io.temporal.common;

import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Representation of a search attribute update inside a workflow. Updates are usually created via
 * {@link SearchAttributeKey#valueSet} or {@link SearchAttributeKey#valueUnset}.
 */
public class SearchAttributeUpdate<T> {

  /**
   * Create an update for setting the search attribute key to a value. Most users will prefer {@link
   * SearchAttributeKey#valueSet}.
   */
  public static <T> SearchAttributeUpdate<T> valueSet(SearchAttributeKey<T> key, @Nonnull T value) {
    return new SearchAttributeUpdate<>(key, value);
  }

  /**
   * Create an update for unsetting a search attribute key. Most users will prefer {@link
   * SearchAttributeKey#valueUnset}.
   */
  public static <T> SearchAttributeUpdate<T> valueUnset(SearchAttributeKey<T> key) {
    return new SearchAttributeUpdate<>(key, null);
  }

  private final SearchAttributeKey<T> key;

  private final T value;

  private SearchAttributeUpdate(SearchAttributeKey<T> key, @Nullable T value) {
    this.key = key;
    this.value = value;
  }

  /** Get the key to set/unset. */
  public SearchAttributeKey<T> getKey() {
    return key;
  }

  /** Get the value to set, or null for unset. */
  public Optional<T> getValue() {
    return Optional.ofNullable(value);
  }
}
