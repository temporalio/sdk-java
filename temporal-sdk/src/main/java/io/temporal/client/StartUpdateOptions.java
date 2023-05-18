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

import com.google.common.base.Objects;
import java.lang.reflect.Type;
import java.util.UUID;

public final class StartUpdateOptions<T> {
  public static <T> StartUpdateOptions.Builder<T> newBuilder() {
    return new StartUpdateOptions.Builder<T>();
  }

  public static StartUpdateOptions.Builder newBuilder(StartUpdateOptions options) {
    return new StartUpdateOptions.Builder(options);
  }

  public static StartUpdateOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final StartUpdateOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = StartUpdateOptions.newBuilder().build();
  }

  private String updateName;
  private String updateId;
  private String firstExecutionRunId;
  private UpdateWaitPolicy waitPolicy;
  private Class<T> resultClass;
  private Type resultType;

  private StartUpdateOptions(
      String updateName,
      String updateId,
      String firstExecutionRunId,
      UpdateWaitPolicy waitPolicy,
      Class<T> resultClass,
      Type resultType) {
    this.updateName = updateName;
    this.updateId = updateId;
    this.firstExecutionRunId = firstExecutionRunId;
    this.waitPolicy = waitPolicy;
    this.resultClass = resultClass;
    this.resultType = resultType;
  }

  public String getUpdateName() {
    return updateName;
  }

  public String getUpdateId() {
    return updateId;
  }

  public String getFirstExecutionRunId() {
    return firstExecutionRunId;
  }

  public UpdateWaitPolicy getWaitPolicy() {
    return waitPolicy;
  }

  public Class<T> getResultClass() {
    return resultClass;
  }

  public Type getResultType() {
    return resultType;
  }

  public StartUpdateOptions.Builder toBuilder() {
    return new StartUpdateOptions.Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    StartUpdateOptions that = (StartUpdateOptions) o;
    return Objects.equal(updateName, that.updateName)
        && updateId == that.updateId
        && firstExecutionRunId == that.firstExecutionRunId
        && waitPolicy.equals(that.waitPolicy)
        && resultClass.equals(that.resultClass)
        && resultType.equals(that.resultType);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        updateName, updateId, firstExecutionRunId, waitPolicy, resultClass, resultType);
  }

  @Override
  public String toString() {
    return "StartUpdateOptions{"
        + "updateName='"
        + updateName
        + ", updateId="
        + updateId
        + ", firstExecutionRunId="
        + firstExecutionRunId
        + ", waitPolicy="
        + waitPolicy
        + ", resultClass="
        + resultClass
        + ", resultType='"
        + resultType
        + '}';
  }

  /**
   * Validates property values.
   *
   * @throws IllegalStateException if validation fails.
   */
  public void validate() {
    if (updateName == null || updateName.isEmpty()) {
      throw new IllegalStateException("updateName must be a non empty string");
    }
    if (resultClass == null) {
      throw new IllegalStateException("resultClass must not be null");
    }
  }

  public static final class Builder<T> {
    private String updateName;
    private String updateId;
    private String firstExecutionRunId;
    private UpdateWaitPolicy waitPolicy;
    private Class<T> resultClass;
    private Type resultType;

    public Builder() {}

    private Builder(StartUpdateOptions<T> options) {
      if (options == null) {
        return;
      }
      this.updateName = options.updateName;
      this.updateId = options.updateId;
      this.firstExecutionRunId = options.firstExecutionRunId;
      this.waitPolicy = options.waitPolicy;
      this.resultClass = options.resultClass;
      this.resultType = options.resultType;
    }

    /** Name of the update handler. Usually it is a method name. */
    public Builder<T> setUpdateName(String updateName) {
      this.updateName = updateName;
      return this;
    }

    /**
     * The update ID is an application-layer identifier for the requested update. It must be unique
     * within the scope of a workflow execution.
     *
     * <p>Default value if not set: <b>Random UUID</b>
     */
    public Builder<T> setUpdateId(String updateId) {
      this.updateId = updateId;
      return this;
    }

    /**
     * The RunID expected to identify the first run in the workflow execution chain. If this
     * expectation does not match then the server will reject the update request with an error.
     *
     * <p>Default value if not set: <b>Empty String</b>
     */
    public Builder<T> setFirstExecutionRunId(String firstExecutionRunId) {
      this.firstExecutionRunId = firstExecutionRunId;
      return this;
    }

    /**
     * Specifies at what point in the update request life cycles this request should return.
     *
     * <p>Default value if not set: <b>Accepted</b>
     *
     * <ul>
     *   <li><b>Accepted</b> Wait for the update to be accepted by the workflow.
     *   <li><b>Completed</b> Wait for the update to be completed by the workflow.
     * </ul>
     */
    public Builder<T> setWaitPolicy(UpdateWaitPolicy waitPolicy) {
      this.waitPolicy = waitPolicy;
      return this;
    }

    /** The class of the update return value. */
    public Builder<T> setResultClass(Class<T> resultClass) {
      this.resultClass = resultClass;
      return this;
    }

    /**
     * The type of the update return value.
     *
     * <p>Default value if not set: <b>resultClass</b>
     */
    public Builder<T> setResultType(Type resultType) {
      this.resultType = resultType;
      return this;
    }

    /** Builds StartUpdateOptions with default values. */
    public StartUpdateOptions<T> build() {
      if (updateId == null || updateId.isEmpty()) {
        updateId = UUID.randomUUID().toString();
      }

      return new StartUpdateOptions<T>(
          updateName,
          updateId,
          firstExecutionRunId == null ? "" : firstExecutionRunId,
          waitPolicy == null ? UpdateWaitPolicy.ACCEPTED : waitPolicy,
          resultClass,
          resultType == null ? resultClass : resultType);
    }
  }
}
