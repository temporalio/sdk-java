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

import java.util.*;
import javax.annotation.Nonnull;

/**
 * Immutable collection of typed search attributes. Use {@link Builder} to create this collection.
 */
public final class SearchAttributes {
  /** An empty search attribute collection. */
  public static final SearchAttributes EMPTY = new SearchAttributes(Collections.emptySortedMap());

  /** Create a new builder to create a search attribute collection. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Create a new builder to create a search attribute collection, copying from an existing
   * collection.
   */
  public static Builder newBuilder(SearchAttributes copyFrom) {
    return new Builder(copyFrom);
  }

  /** Builder for creating a search attribute collection. */
  public static class Builder {
    private SortedMap<SearchAttributeKey<?>, Object> untypedValues;

    private Builder() {
      untypedValues = new TreeMap<>();
    }

    private Builder(SearchAttributes copyFrom) {
      untypedValues = new TreeMap<>(copyFrom.untypedValues);
    }

    /** Set a search attribute key and typed value. */
    public <T> Builder set(SearchAttributeKey<T> key, @Nonnull T value) {
      Objects.requireNonNull(untypedValues, "Collection already built");
      Objects.requireNonNull(value, "Value cannot be null");
      // TODO(cretz): Prevent duplicate key name?
      untypedValues.put(key, value);
      return this;
    }

    /** Unset a search attribute key. */
    public Builder unset(SearchAttributeKey<?> key) {
      Objects.requireNonNull(untypedValues, "Collection already built");
      untypedValues.remove(key);
      return this;
    }

    /** Build the search attribute collection. This builder cannot be used after this. */
    public SearchAttributes build() {
      // Create unmodifiable view and set untyped values to null. We choose to mark as null instead
      // of add an extra copy here.
      SearchAttributes attrs =
          new SearchAttributes(Collections.unmodifiableSortedMap(untypedValues));
      untypedValues = null;
      return attrs;
    }
  }

  private final SortedMap<SearchAttributeKey<?>, Object> untypedValues;

  private SearchAttributes(SortedMap<SearchAttributeKey<?>, Object> untypedValues) {
    this.untypedValues = untypedValues;
  }

  /**
   * Get a search attribute value by its key or null if not present.
   *
   * @throws ClassCastException If the search attribute is not of the proper type for the key.
   */
  @SuppressWarnings("unchecked")
  public <T> T get(SearchAttributeKey<T> key) {
    // We intentionally let a failed cast throw here
    return (T) untypedValues.get(key);
  }

  /** Get whether the search attribute key exists. */
  public boolean containsKey(SearchAttributeKey<?> key) {
    return untypedValues.containsKey(key);
  }

  /** Get the size of the collection. */
  public int size() {
    return untypedValues.size();
  }

  /** Get the immutable, untyped sorted map. */
  public SortedMap<SearchAttributeKey<?>, Object> getUntypedValues() {
    return untypedValues;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SearchAttributes that = (SearchAttributes) o;
    return untypedValues.equals(that.untypedValues);
  }

  @Override
  public int hashCode() {
    return Objects.hash(untypedValues);
  }
}
