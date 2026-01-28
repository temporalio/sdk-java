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
