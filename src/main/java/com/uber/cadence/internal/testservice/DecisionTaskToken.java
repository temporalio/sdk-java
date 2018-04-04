/*
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

package com.uber.cadence.internal.testservice;

import com.google.common.base.Throwables;
import com.uber.cadence.InternalServiceError;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

final class DecisionTaskToken {

  private final ExecutionId executionId;
  private final int historySize;

  DecisionTaskToken(ExecutionId executionId, int historySize) {
    this.executionId = Objects.requireNonNull(executionId);
    this.historySize = Objects.requireNonNull(historySize);
  }

  ExecutionId getExecutionId() {
    return executionId;
  }

  int getHistorySize() {
    return historySize;
  }

  /** Used for task tokens. */
  byte[] toBytes() throws InternalServiceError {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bout);
    try {
      addBytes(out);
    } catch (IOException e) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(e));
    }
    return bout.toByteArray();
  }

  private void addBytes(DataOutputStream out) throws IOException {
    executionId.addBytes(out);
    out.writeInt(historySize);
  }

  static DecisionTaskToken fromBytes(byte[] serialized) throws InternalServiceError {
    ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
    DataInputStream in = new DataInputStream(bin);
    try {
      ExecutionId executionId = ExecutionId.readFromBytes(in);
      int historySize = in.readInt();
      return new DecisionTaskToken(executionId, historySize);
    } catch (IOException e) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(e));
    }
  }
}
