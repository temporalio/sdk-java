package io.temporal.opentelemetry.provider;

import io.temporal.opentelemetry.SpanBuilderProvider;
import io.temporal.opentelemetry.SpanCreationContext;
import io.temporal.opentelemetry.SpanOperationType;
import io.temporal.opentelemetry.internal.ActionTypeAndNameSpanBuilderProvider;
import java.util.HashMap;
import java.util.Map;

/**
 * This implementation of {@link SpanBuilderProvider} names and tags the OpenTelemetry spans in a
 * way that is compatible with DataDog's APM tooling.
 *
 * <p>Spans are named with the operation type default prefixes from {@link
 * SpanOperationType#getDefaultPrefix()} (e.g. "StartActivity") and set a tag with key
 * "resource.name" and the value is the name of the activity or workflow or child workflow. See <a
 * href="https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-api/src/main/java/datadog/trace/api/DDTags.java#L7">here</a>
 * for the datadog standard tag names.
 */
public class DataDogOpenTelemetrySpanBuilderProvider extends ActionTypeAndNameSpanBuilderProvider {
  public static final DataDogOpenTelemetrySpanBuilderProvider INSTANCE =
      new DataDogOpenTelemetrySpanBuilderProvider();

  private static final String DD_RESOURCE_NAME_TAG = "resource.name";

  /** Uses just the operation type as the name, e.g. "StartActivity" */
  @Override
  protected String getSpanName(SpanCreationContext context) {
    return context.getSpanOperationType().getDefaultPrefix();
  }

  /**
   * Includes the default tags but also uses {@link SpanCreationContext#getActionName()} as a tag
   * with the key "resource.name"
   */
  @Override
  protected Map<String, String> getSpanTags(SpanCreationContext context) {
    Map<String, String> tags = new HashMap<>();
    tags.putAll(super.getSpanTags(context));
    tags.put(DD_RESOURCE_NAME_TAG, context.getActionName());
    return tags;
  }
}
