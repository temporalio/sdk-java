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
