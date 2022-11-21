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

package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import java.io.*;
import java.util.Objects;
import javax.annotation.Nonnull;

class ActivityTaskToken {
  @Nonnull private final ExecutionId executionId;
  private final long scheduledEventId;
  private final int attempt;

  ActivityTaskToken(
      @Nonnull String namespace,
      @Nonnull WorkflowExecution execution,
      long scheduledEventId,
      int attempt) {
    this(
        new ExecutionId(Objects.requireNonNull(namespace), Objects.requireNonNull(execution)),
        scheduledEventId,
        attempt);
  }

  ActivityTaskToken(
      @Nonnull String namespace,
      @Nonnull String workflowId,
      @Nonnull String runId,
      long scheduledEventId,
      int attempt) {
    this(
        namespace,
        WorkflowExecution.newBuilder()
            .setWorkflowId(Objects.requireNonNull(workflowId))
            .setRunId(Objects.requireNonNull(runId))
            .build(),
        scheduledEventId,
        attempt);
  }

  ActivityTaskToken(@Nonnull ExecutionId executionId, long scheduledEventId, int attempt) {
    this.executionId = Objects.requireNonNull(executionId);
    this.scheduledEventId = scheduledEventId;
    this.attempt = attempt;
  }

  @Nonnull
  public ExecutionId getExecutionId() {
    return executionId;
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public long getAttempt() {
    return attempt;
  }

  /** Used for task tokens. */
  public ByteString toBytes() {
    try (ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bout)) {
      out.writeUTF(executionId.getNamespace());
      WorkflowExecution execution = executionId.getExecution();
      out.writeUTF(execution.getWorkflowId());
      out.writeUTF(execution.getRunId());
      out.writeLong(scheduledEventId);
      out.writeInt(attempt);
      return ByteString.copyFrom(bout.toByteArray());
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  static ActivityTaskToken fromBytes(ByteString serialized) {
    return fromBytes(serialized.toByteArray());
  }

  static ActivityTaskToken fromBytes(byte[] serialized) {
    try (ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
        DataInputStream in = new DataInputStream(bin)) {
      String namespace = in.readUTF();
      String workflowId = in.readUTF();
      String runId = in.readUTF();
      long scheduledEventId = in.readLong();
      int attempt = in.readInt();
      return new ActivityTaskToken(namespace, workflowId, runId, scheduledEventId, attempt);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityTaskToken that = (ActivityTaskToken) o;
    return scheduledEventId == that.scheduledEventId
        && attempt == that.attempt
        && executionId.equals(that.executionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executionId, scheduledEventId, attempt);
  }

  @Override
  public String toString() {
    return "ActivityTaskToken{"
        + "executionId="
        + executionId
        + ", scheduledEventId="
        + scheduledEventId
        + ", attempt="
        + attempt
        + '}';
  }
}
