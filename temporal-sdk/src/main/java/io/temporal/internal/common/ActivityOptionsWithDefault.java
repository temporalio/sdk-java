package io.temporal.internal.common;

import io.temporal.activity.ActivityOptions;
import java.util.HashMap;
import java.util.Map;

public final class ActivityOptionsWithDefault {
  private ActivityOptions defaultOptions;

  private Map<String, ActivityOptions> optionsMap;

  private final ActivityOptionsWithDefault overridden;

  public ActivityOptionsWithDefault(
      ActivityOptionsWithDefault overridden,
      ActivityOptions defaultOptions,
      Map<String, ActivityOptions> optionsMap) {
    this.overridden = overridden;
    this.defaultOptions = defaultOptions;
    this.optionsMap = new HashMap<>(optionsMap);
  }

  public ActivityOptionsWithDefault(ActivityOptionsWithDefault overridden) {
    this.overridden = overridden;
    defaultOptions = null;
    optionsMap = null;
  }

  public void setDefaultOptions(ActivityOptions defaultOptions) {
    this.defaultOptions = defaultOptions;
  }

  public void setOptionsMap(Map<String, ActivityOptions> optionsMap) {
    this.optionsMap = optionsMap;
  }

  public ActivityOptions getMergedOptions(String activityType) {
    ActivityOptions overrideOptions = overridden.getMergedOptions(activityType);
    return merge(overrideOptions, defaultOptions, optionsMap.get(activityType));
  }

  /** later options override the previous ones */
  private static ActivityOptions merge(ActivityOptions... options) {
    if (options == null || options.length == 0) {
      return null;
    }
    ActivityOptions result = options[0];
    for (int i = 1; i < options.length; i++) {
      result = result.toBuilder().mergeActivityOptions(options[i]).build();
    }
    return result;
  }
}
