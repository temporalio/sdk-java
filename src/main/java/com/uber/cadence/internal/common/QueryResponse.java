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

package com.uber.cadence.internal.common;

import com.uber.cadence.QueryRejected;

public final class QueryResponse<T> {
  private final QueryRejected queryRejected;
  private final T result;

  public QueryResponse(QueryRejected queryRejected, T result) {
    this.queryRejected = queryRejected;
    this.result = result;
  }

  /**
   * Returns the value of the QueryRejected property for this object.
   *
   * @return The value of the QueryRejected property for this object.
   */
  public QueryRejected getQueryRejected() {
    return queryRejected;
  }

  /**
   * Returns the value of the Result property for this object.
   *
   * @return The value of the Result property for this object.
   */
  public T getResult() {
    return result;
  }
}
