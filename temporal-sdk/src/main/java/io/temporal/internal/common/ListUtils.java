package io.temporal.internal.common;

import java.util.ArrayList;
import java.util.List;

public final class ListUtils {

  private ListUtils() {}

  /** Concatenates a list of lists into a single list, preserving order. */
  public static <T> List<T> flatten(List<? extends List<? extends T>> lists) {
    List<T> result = new ArrayList<>();
    for (List<? extends T> list : lists) {
      result.addAll(list);
    }
    return result;
  }
}
