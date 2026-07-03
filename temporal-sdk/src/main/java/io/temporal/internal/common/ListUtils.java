package io.temporal.internal.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ListUtils {

  private ListUtils() {}

  public static <T> List<T> flatten(Collection<? extends Collection<? extends T>> collections) {
    List<T> result = new ArrayList<>();
    for (Collection<? extends T> collection : collections) {
      result.addAll(collection);
    }
    return result;
  }
}
