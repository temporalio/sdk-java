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

package io.temporal.workflow;

/**
 * These are the system attributes that are provided by default. Use {@link #name()} to get a key
 * for our Search Attributes objects map.
 */
public enum SystemSearchAttributes {
  BatcherNamespace,
  BatcherUser,
  BinaryChecksums,
  CloseTime,
  ExecutionDuration,
  ExecutionStatus,
  ExecutionTime,
  HistoryLength,
  RunId,
  StartTime,
  StateTransitionCount,
  TaskQueue,
  TemporalChangeVersion,
  WorkflowId,
  WorkflowType,
  CustomKeywordField,
  CustomStringField,
  CustomIntField,
  CustomDoubleField,
  CustomBoolField,
  CustomDatetimeField
}
