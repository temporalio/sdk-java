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

package io.temporal.internal.context;

import io.temporal.common.context.ContextPropagator;
import java.util.ArrayList;
import java.util.List;

public class ContextActivityThreadLocal extends AbstractContextThreadLocal {

  private static final ThreadLocal<List<ContextPropagator>> contextPropagators =
      ThreadLocal.withInitial(() -> new ArrayList<>());

  public static ContextActivityThreadLocal getInstance() {
    return new ContextActivityThreadLocal();
  }

  @Override
  public List<ContextPropagator> getPropagatorsForThread() {
    return contextPropagators.get();
  }

  @Override
  public void setContextPropagators(List<ContextPropagator> contextPropagators) {
    if (contextPropagators == null || contextPropagators.size() == 0) {
      return;
    }

    this.contextPropagators.set(contextPropagators);
  }
}
