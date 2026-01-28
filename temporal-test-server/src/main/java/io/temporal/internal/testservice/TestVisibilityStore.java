package io.temporal.internal.testservice;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.enums.v1.IndexedValueType;
import java.io.Closeable;
import java.util.Map;
import javax.annotation.Nonnull;

public interface TestVisibilityStore extends Closeable {
  void addSearchAttribute(String name, IndexedValueType type);

  void removeSearchAttribute(String name);

  Map<String, IndexedValueType> getRegisteredSearchAttributes();

  SearchAttributes getSearchAttributesForExecution(ExecutionId executionId);

  SearchAttributes upsertSearchAttributesForExecution(
      ExecutionId executionId, @Nonnull SearchAttributes searchAttributes);

  void validateSearchAttributes(SearchAttributes searchAttributes);

  @Override
  void close();
}
