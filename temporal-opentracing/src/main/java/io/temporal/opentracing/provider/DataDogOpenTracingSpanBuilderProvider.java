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
import io.temporal.opentracing.internal.SpanBuilderFromSpanContentProvider;
import java.util.HashMap;
import java.util.Map;

/**
 * This implementation of {@link SpanBuilderProvider} names and tags the OpenTracing spans in a way
 * that is compatible with DataDog's APM tooling.
 */
public class DataDogOpenTracingSpanBuilderProvider extends SpanBuilderFromSpanContentProvider
    implements SpanBuilderProvider {

  /**
   * Uses just the operation type as the name, e.g. "StartActivity"
   *
   * @param context
   * @return
   */
  @Override
  protected String getSpanName(SpanCreationContext context) {
    return context.getSpanOperationType().getDefaultPrefix();
  }

  /**
   * Includes the default tags but also uses {@link SpanCreationContext#getOperationName()} as a tag
   * with the key "resource.name"
   *
   * @param context
   * @return
   */
  @Override
  protected Map<String, String> getSpanTags(SpanCreationContext context) {
    Map<String, String> tags = new HashMap<>();
    tags.putAll(super.getSpanTags(context));
    tags.put("resource.name", context.getOperationName());
    return tags;
  }
}
