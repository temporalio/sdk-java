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
