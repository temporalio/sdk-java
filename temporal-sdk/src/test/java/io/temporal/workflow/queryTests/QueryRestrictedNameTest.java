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

package io.temporal.workflow.queryTests;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class QueryRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedQueryMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(
                        QueryMethodWithOverrideNameRestrictedImpl.class));
    Assert.assertEquals(
        "query name \"__temporal_query\" must not start with \"__temporal_\"", e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(QueryMethodNameRestrictedImpl.class));
    Assert.assertEquals(
        "query name \"__temporal_query\" must not start with \"__temporal_\"", e.getMessage());
  }

  @WorkflowInterface
  public interface QueryMethodWithOverrideNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @QueryMethod(name = "__temporal_query")
    int queryMethod();
  }

  public static class QueryMethodWithOverrideNameRestrictedImpl
      implements QueryMethodWithOverrideNameRestricted {

    @Override
    public void workflowMethod() {}

    @Override
    public int queryMethod() {
      return 0;
    }
  }

  @WorkflowInterface
  public interface QueryMethodNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @QueryMethod()
    int __temporal_query();
  }

  public static class QueryMethodNameRestrictedImpl implements QueryMethodNameRestricted {
    @Override
    public void workflowMethod() {}

    @Override
    public int __temporal_query() {
      return 0;
    }
  }
}
