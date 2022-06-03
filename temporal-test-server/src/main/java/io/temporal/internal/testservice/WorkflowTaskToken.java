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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

final class WorkflowTaskToken {

  private final ExecutionId executionId;
  private final int historySize;

  WorkflowTaskToken(ExecutionId executionId, int historySize) {
    this.executionId = Objects.requireNonNull(executionId);
    this.historySize = historySize;
  }

  ExecutionId getExecutionId() {
    return executionId;
  }

  int getHistorySize() {
    return historySize;
  }

  /** Used for task tokens. */
  ByteString toBytes() {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bout);
    try {
      addBytes(out);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
    }
    return ByteString.copyFrom(bout.toByteArray());
  }

  private void addBytes(DataOutputStream out) throws IOException {
    executionId.addBytes(out);
    out.writeInt(historySize);
  }

  static WorkflowTaskToken fromBytes(ByteString serialized) {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized.toByteArray());
    DataInputStream in = new DataInputStream(bin);
    try {
      ExecutionId executionId = ExecutionId.readFromBytes(in);
      int historySize = in.readInt();
      return new WorkflowTaskToken(executionId, historySize);
    } catch (IOException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Failure parsing workflow task token")
          .withCause(e)
          .asRuntimeException();
    }
  }
}
