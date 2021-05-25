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

package io.temporal.opentracing;

import java.util.Map;

/** Allows a pluggable mechanism to configure an OpenTracing span's name and tags */
public interface OperationNameAndTagsProvider {

  /**
   * Returns the name of the span, given the operation type and type name
   *
   * @param context The start span context
   * @return The span name
   */
  String getSpanName(StartSpanContext context);

  /**
   * Returns the tags for the span
   *
   * @param context The start span context
   * @return The complete set of tags for the span, including the default tags if desired
   */
  Map<String, String> getSpanTags(StartSpanContext context);
}
