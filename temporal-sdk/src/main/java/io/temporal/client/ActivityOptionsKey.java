package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;

/**
 * Typed key for one updatable activity option.
 *
 * <p>Use the keys on {@link ActivityOptionsKeys} rather than constructing these directly.
 *
 * @param <T> type of the option's value
 */
@Experimental
public final class ActivityOptionsKey<T> {

  private final String name;
  private final Class<T> valueType;

  ActivityOptionsKey(String name, Class<T> valueType) {
    this.name = name;
    this.valueType = valueType;
  }

  /** Field-mask path this key updates. */
  public String getName() {
    return name;
  }

  /** Type of this key's value. */
  public Class<T> getValueType() {
    return valueType;
  }

  /** Create an update that sets this option to the given value. */
  public ActivityOptionsUpdate<T> valueSet(@Nonnull T value) {
    return ActivityOptionsUpdate.valueSet(this, value);
  }

  /** Create an update that clears this option server-side. */
  public ActivityOptionsUpdate<T> valueUnset() {
    return ActivityOptionsUpdate.valueUnset(this);
  }

  @Override
  public String toString() {
    return "ActivityOptionsKey{name='" + name + "', valueType=" + valueType.getSimpleName() + '}';
  }
}
