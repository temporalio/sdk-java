/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.opentracing.provider;

import io.temporal.opentracing.SpanBuilderProvider;
import io.temporal.opentracing.SpanCreationContext;
import io.temporal.opentracing.SpanOperationType;
import io.temporal.opentracing.internal.ActionTypeAndNameSpanBuilderProvider;
import java.util.HashMap;
import java.util.Map;

/**
 * This implementation of {@link SpanBuilderProvider} names and tags the OpenTracing spans in a way
 * that is compatible with DataDog's APM tooling.
 *
 * <p>Spans are named with the operation type default prefixes from {@link
 * SpanOperationType#getDefaultPrefix()} (e.g. "StartActivity") and set a tag with key
 * "resource.name" and the value is the name of the activity or workflow or child workflow. See <a
 * href="https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-api/src/main/java/datadog/trace/api/DDTags.java#L7">here</a>
 * for the datadog standard tag names.
 */
public class DataDogOpenTracingSpanBuilderProvider extends ActionTypeAndNameSpanBuilderProvider {

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
