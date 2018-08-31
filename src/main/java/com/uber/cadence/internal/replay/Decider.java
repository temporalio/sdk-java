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

package com.uber.cadence.internal.replay;

import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.internal.worker.DecisionTaskWithHistoryIterator;

public interface Decider {

  // TODO: refactor in future CR. Merge methods and decide should return a list of decisions.
  void decide(DecisionTaskWithHistoryIterator iterator) throws Throwable;

  byte[] query(DecisionTaskWithHistoryIterator decisionTaskIterator, WorkflowQuery query)
      throws Throwable;

  void close();
}
