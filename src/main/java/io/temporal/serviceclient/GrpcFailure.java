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

package io.temporal.serviceclient;

public enum GrpcFailure {
  CANCELLATION_ALREADY_REQUESTED,
  CLIENT_VERSION_NOT_SUPPORTED,
  CURRENT_BRANCH_CHANGED,
  DOMAIN_ALREADY_EXISTS_FAILURE,
  DOMAIN_NOT_ACTIVE,
  EVENT_ALREADY_STARTED,
  QUERY_FAILED,
  RETRY_TASK,
  RETRY_TASK_V2,
  SHARD_OWNERSHIP_LOST,
  WORKFLOW_EXECUTION_ALREADY_STARTED_FAILURE
}
