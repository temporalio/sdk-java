/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.opentracing;

import io.opentracing.mock.MockSpan;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OpenTracingSpansHelper {
  private final List<MockSpan> mockSpans;
  private final Map<Long, MockSpan> spanIdToSpan;

  public OpenTracingSpansHelper(List<MockSpan> mockSpans) {
    this.mockSpans = mockSpans;
    this.spanIdToSpan =
        mockSpans.stream().collect(Collectors.toMap(span -> span.context().spanId(), span -> span));
  }

  public MockSpan getSpan(long spanId) {
    return spanIdToSpan.get(spanId);
  }

  public MockSpan getSpanByOperationName(String operationName) {
    return mockSpans.stream()
        .filter(span -> span.operationName().equals(operationName))
        .findFirst()
        .orElse(null);
  }

  public List<MockSpan> getByParentSpan(MockSpan parentSpan) {
    return mockSpans.stream()
        .filter(span -> span.parentId() == parentSpan.context().spanId())
        .collect(Collectors.toList());
  }
}
