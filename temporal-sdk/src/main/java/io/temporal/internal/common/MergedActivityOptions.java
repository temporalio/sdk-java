package io.temporal.internal.common;

import io.temporal.activity.ActivityOptions;
import java.util.HashMap;
import java.util.Map;

/**
 * The chain of ActivityOptions and per type options maps. Used to merge options specified at the
 * following layers:
 *
 * <pre>
 *     * WorkflowImplementationOptions
 *     * Workflow
 *     * ActivityStub
 * </pre>
 *
 * Each next layer overrides specific options specified at the previous layer.
 */
public final class MergedActivityOptions {

  /** Common options across all activity types. */
  private ActivityOptions defaultOptions;

  /** Per activity type options. These override defaultOptions. */
  private Map<String, ActivityOptions> optionsMap;

  /** The options specified at the previous layer. They are overriden by this object. */
  private final MergedActivityOptions overridden;

  public MergedActivityOptions(
      MergedActivityOptions overridden,
      ActivityOptions defaultOptions,
      Map<String, ActivityOptions> optionsMap) {
    this.overridden = overridden;
    this.defaultOptions = defaultOptions;
    this.optionsMap = new HashMap<>(optionsMap);
  }

  public MergedActivityOptions(MergedActivityOptions overridden) {
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

  /** Get merged options for the given activityType. */
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
