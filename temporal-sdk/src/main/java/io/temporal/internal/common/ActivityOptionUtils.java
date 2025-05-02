package io.temporal.internal.common;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import java.util.Map;
import javax.annotation.Nonnull;

public class ActivityOptionUtils {
  public static void mergePredefinedActivityOptions(
      @Nonnull Map<String, ActivityOptions> mergeTo,
      @Nonnull Map<String, ActivityOptions> override) {
    override.forEach(
        (key, value) ->
            mergeTo.merge(key, value, (o1, o2) -> o1.toBuilder().mergeActivityOptions(o2).build()));
  }

  public static void mergePredefinedLocalActivityOptions(
      @Nonnull Map<String, LocalActivityOptions> mergeTo,
      @Nonnull Map<String, LocalActivityOptions> override) {
    override.forEach(
        (key, value) ->
            mergeTo.merge(key, value, (o1, o2) -> o1.toBuilder().mergeActivityOptions(o2).build()));
  }
}
