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

package io.temporal.internal.sync;

import static org.mockito.Mockito.*;

import io.temporal.converter.JsonDataConverter;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.replay.DecisionContext;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.workflow.WorkflowInvoker;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class SyncDecisionContextTest {
  SyncDecisionContext context;
  DecisionContext mockDecisionContext = mock(DecisionContext.class);;

  @Before
  public void setUp() {
    this.context =
        new SyncDecisionContext(
            mockDecisionContext,
            JsonDataConverter.getInstance(),
            null,
            (args, interceptor, next) ->
                new WorkflowInvoker() {
                  @Override
                  public Object execute(Object[] arguments) {
                    return next.execute(arguments, interceptor);
                  }

                  @Override
                  public void processSignal(String signalName, Object[] arguments, long eventId) {
                    next.processSignal(signalName, arguments, eventId);
                  }
                },
            null);
  }

  @Test
  public void testUpsertSearchAttributes() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    attr.put("CustomKeywordField", "keyword");
    SearchAttributes serializedAttr = InternalUtils.convertMapToSearchAttributes(attr);

    context.upsertSearchAttributes(attr);
    verify(mockDecisionContext, times(1)).upsertSearchAttributes(serializedAttr);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUpsertSearchAttributesException() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    context.upsertSearchAttributes(attr);
  }
}
