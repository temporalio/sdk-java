package io.temporal.common;

import com.google.common.reflect.TypeToken;
import io.temporal.api.enums.v1.IndexedValueType;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Representation of a typed search attribute key. */
public class SearchAttributeKey<T> implements Comparable<SearchAttributeKey<T>> {
  private static final Type KEYWORD_LIST_REFLECT_TYPE = new TypeToken<List<String>>() {}.getType();

  /** Create a search attribute key for a text attribute type. */
  public static SearchAttributeKey<String> forText(String name) {
    return new SearchAttributeKey<>(name, IndexedValueType.INDEXED_VALUE_TYPE_TEXT, String.class);
  }

  /** Create a search attribute key for a keyword attribute type. */
  public static SearchAttributeKey<String> forKeyword(String name) {
    return new SearchAttributeKey<>(
        name, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD, String.class);
  }

  /** Create a search attribute key for an int attribute type. */
  public static SearchAttributeKey<Long> forLong(String name) {
    return new SearchAttributeKey<>(name, IndexedValueType.INDEXED_VALUE_TYPE_INT, Long.class);
  }

  /** Create a search attribute key for a double attribute type. */
  public static SearchAttributeKey<Double> forDouble(String name) {
    return new SearchAttributeKey<>(name, IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE, Double.class);
  }

  /** Create a search attribute key for a boolean attribute type. */
  public static SearchAttributeKey<Boolean> forBoolean(String name) {
    return new SearchAttributeKey<>(name, IndexedValueType.INDEXED_VALUE_TYPE_BOOL, Boolean.class);
  }

  /** Create a search attribute key for a datetime attribute type. */
  public static SearchAttributeKey<OffsetDateTime> forOffsetDateTime(String name) {
    return new SearchAttributeKey<>(
        name, IndexedValueType.INDEXED_VALUE_TYPE_DATETIME, OffsetDateTime.class);
  }

  /** Create a search attribute key for a keyword list attribute type. */
  public static SearchAttributeKey<List<String>> forKeywordList(String name) {
    return new SearchAttributeKey<>(
        name,
        IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD_LIST,
        List.class,
        KEYWORD_LIST_REFLECT_TYPE);
  }

  private final String name;
  private final IndexedValueType valueType;
  private final Class<? super T> valueClass;
  private final Type valueReflectType;

  private SearchAttributeKey(String name, IndexedValueType valueType, Class<? super T> valueClass) {
    this(name, valueType, valueClass, valueClass);
  }

  private SearchAttributeKey(
      String name, IndexedValueType valueType, Class<? super T> valueClass, Type valueReflectType) {
    this.name = name;
    this.valueType = valueType;
    this.valueClass = valueClass;
    this.valueReflectType = valueReflectType;
  }

  /** Get the name of the search attribute. */
  public String getName() {
    return name;
  }

  /** Get the search attribute value type. */
  public IndexedValueType getValueType() {
    return valueType;
  }

  /** Get the class that the search attribute value will be. */
  public Class<? super T> getValueClass() {
    return valueClass;
  }

  /**
   * Get the reflect type that the search attribute will be. For all key types except keyword list,
   * this is the same as {@link #getValueType}.
   */
  public Type getValueReflectType() {
    return valueReflectType;
  }

  /** Create an update that sets a value for this key. */
  public SearchAttributeUpdate<T> valueSet(@Nonnull T value) {
    return SearchAttributeUpdate.valueSet(this, value);
  }

  /** Create an update that unsets a value for this key. */
  public SearchAttributeUpdate<T> valueUnset() {
    return SearchAttributeUpdate.valueUnset(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SearchAttributeKey<?> that = (SearchAttributeKey<?>) o;
    return name.equals(that.name)
        && valueType == that.valueType
        && valueClass.equals(that.valueClass);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, valueType, valueClass);
  }

  @Override
  public int compareTo(SearchAttributeKey<T> o) {
    int c = name.compareTo(o.name);
    if (c == 0) {
      c = valueType.compareTo(o.valueType);
    }
    if (c == 0) {
      c = valueClass.getName().compareTo(o.valueClass.getName());
    }
    return c;
  }
}
