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

package io.temporal.client;

import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class WorkflowExecutionMetadata {
  private final @Nonnull WorkflowExecutionInfo info;
  private final @Nonnull DataConverter dataConverter;

  WorkflowExecutionMetadata(
      @Nonnull WorkflowExecutionInfo info, @Nonnull DataConverter dataConverter) {
    this.info = Preconditions.checkNotNull(info, "info");
    this.dataConverter = Preconditions.checkNotNull(dataConverter, "dataConverter");
  }

  @Nonnull
  public WorkflowExecution getExecution() {
    return info.getExecution();
  }

  @Nonnull
  public String getWorkflowType() {
    return info.getType().getName();
  }

  @Nonnull
  public String getTaskQueue() {
    return info.getTaskQueue();
  }

  @Nonnull
  public Instant getStartTime() {
    return ProtobufTimeUtils.toJavaInstant(info.getStartTime());
  }

  @Nonnull
  public Instant getExecutionTime() {
    return ProtobufTimeUtils.toJavaInstant(info.getExecutionTime());
  }

  @Nullable
  public Instant getCloseTime() {
    return info.hasCloseTime() ? ProtobufTimeUtils.toJavaInstant(info.getCloseTime()) : null;
  }

  @Nonnull
  public WorkflowExecutionStatus getStatus() {
    return info.getStatus();
  }

  public long getHistoryLength() {
    return info.getHistoryLength();
  }

  @Nullable
  public String getParentNamespace() {
    return info.hasParentExecution() ? info.getParentNamespaceId() : null;
  }

  @Nullable
  public WorkflowExecution getParentExecution() {
    return info.hasParentExecution() ? info.getParentExecution() : null;
  }

  @Nonnull
  public Map<String, List<?>> getSearchAttributes() {
    return Collections.unmodifiableMap(SearchAttributesUtil.decode(info.getSearchAttributes()));
  }

  @Nullable
  public <T> Object getMemo(String key, Class<T> valueClass) {
    return getMemo(key, valueClass, valueClass);
  }

  @Nullable
  public <T> T getMemo(String key, Class<T> valueClass, Type genericType) {
    Payload memo = info.getMemo().getFieldsMap().get(key);
    if (memo == null) {
      return null;
    }
    return dataConverter.fromPayload(memo, valueClass, genericType);
  }

  @Nonnull
  public WorkflowExecutionInfo getWorkflowExecutionInfo() {
    return info;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowExecutionMetadata that = (WorkflowExecutionMetadata) o;
    return info.equals(that.info);
  }

  @Override
  public int hashCode() {
    return Objects.hash(info);
  }
}
