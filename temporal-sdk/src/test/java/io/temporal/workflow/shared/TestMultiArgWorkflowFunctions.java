package io.temporal.workflow.shared;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

public class TestMultiArgWorkflowFunctions {

  public interface TestNoArgsUpdateFunc {

    @UpdateMethod
    String update();
  }

  public interface Test1ArgUpdateFunc {

    @UpdateMethod
    String update1(String input);
  }

  public interface Test2ArgsUpdateFunc {

    @UpdateMethod
    String update2(String a1, int a2);
  }

  public interface Test3ArgsUpdateFunc {

    @UpdateMethod
    String update3(String a1, int a2, int a3);
  }

  public interface Test4ArgsUpdateFunc {

    @UpdateMethod
    String update4(String a1, int a2, int a3, int a4);
  }

  public interface Test5ArgsUpdateFunc {

    @UpdateMethod
    String update5(String a1, int a2, int a3, int a4, int a5);
  }

  public interface Test6ArgsUpdateFunc {

    @UpdateMethod
    String update6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  @WorkflowInterface
  public interface TestNoArgsWorkflowFunc extends TestNoArgsUpdateFunc {

    @WorkflowMethod
    String func();
  }

  @WorkflowInterface
  public interface Test1ArgWorkflowFunc extends Test1ArgUpdateFunc {

    @WorkflowMethod(name = "func1")
    String func1(String input);
  }

  @WorkflowInterface
  public interface Test2ArgWorkflowFunc extends Test2ArgsUpdateFunc {

    @WorkflowMethod
    String func2(String a1, int a2);
  }

  @WorkflowInterface
  public interface Test3ArgWorkflowFunc extends Test3ArgsUpdateFunc {

    @WorkflowMethod
    String func3(String a1, int a2, int a3);
  }

  @WorkflowInterface
  public interface Test4ArgWorkflowFunc extends Test4ArgsUpdateFunc {

    @WorkflowMethod
    String func4(String a1, int a2, int a3, int a4);
  }

  @WorkflowInterface
  public interface Test5ArgWorkflowFunc extends Test5ArgsUpdateFunc {

    @WorkflowMethod
    String func5(String a1, int a2, int a3, int a4, int a5);
  }

  @WorkflowInterface
  public interface Test6ArgWorkflowFunc extends Test6ArgsUpdateFunc {

    @WorkflowMethod
    String func6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  public interface ProcInvocationQueryable {

    @QueryMethod(name = "getTrace")
    String query();
  }

  @WorkflowInterface
  public interface TestNoArgsWorkflowProc extends ProcInvocationQueryable, TestNoArgsUpdateFunc {

    @WorkflowMethod
    void proc();
  }

  @WorkflowInterface
  public interface Test1ArgWorkflowProc extends ProcInvocationQueryable, Test1ArgUpdateFunc {

    @WorkflowMethod
    void proc1(String input);
  }

  @WorkflowInterface
  public interface Test2ArgWorkflowProc extends ProcInvocationQueryable, Test2ArgsUpdateFunc {

    @WorkflowMethod
    void proc2(String a1, int a2);
  }

  @WorkflowInterface
  public interface Test3ArgWorkflowProc extends ProcInvocationQueryable, Test3ArgsUpdateFunc {

    @WorkflowMethod
    void proc3(String a1, int a2, int a3);
  }

  @WorkflowInterface
  public interface Test4ArgWorkflowProc extends ProcInvocationQueryable, Test4ArgsUpdateFunc {

    @WorkflowMethod
    void proc4(String a1, int a2, int a3, int a4);
  }

  @WorkflowInterface
  public interface Test5ArgWorkflowProc extends ProcInvocationQueryable, Test5ArgsUpdateFunc {

    @WorkflowMethod
    void proc5(String a1, int a2, int a3, int a4, int a5);
  }

  @WorkflowInterface
  public interface Test6ArgWorkflowProc extends ProcInvocationQueryable, Test6ArgsUpdateFunc {

    @WorkflowMethod
    void proc6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  public static class TestMultiArgWorkflowImpl
      implements TestNoArgsWorkflowFunc,
          Test1ArgWorkflowFunc,
          Test2ArgWorkflowFunc,
          Test3ArgWorkflowFunc,
          Test4ArgWorkflowFunc,
          Test5ArgWorkflowFunc,
          Test6ArgWorkflowFunc,
          TestNoArgsWorkflowProc,
          Test1ArgWorkflowProc,
          Test2ArgWorkflowProc,
          Test3ArgWorkflowProc,
          Test4ArgWorkflowProc,
          Test5ArgWorkflowProc,
          Test6ArgWorkflowProc {

    private String procResult;

    @Override
    public String func() {
      return "func";
    }

    @Override
    public String func1(String a1) {
      return a1;
    }

    @Override
    public String func2(String a1, int a2) {
      return a1 + a2;
    }

    @Override
    public String func3(String a1, int a2, int a3) {
      return a1 + a2 + a3;
    }

    @Override
    public String func4(String a1, int a2, int a3, int a4) {
      return a1 + a2 + a3 + a4;
    }

    @Override
    public String func5(String a1, int a2, int a3, int a4, int a5) {
      return a1 + a2 + a3 + a4 + a5;
    }

    @Override
    public String func6(String a1, int a2, int a3, int a4, int a5, int a6) {
      return a1 + a2 + a3 + a4 + a5 + a6;
    }

    @Override
    public void proc() {
      procResult = "proc";
    }

    @Override
    public void proc1(String a1) {
      procResult = a1;
    }

    @Override
    public void proc2(String a1, int a2) {
      procResult = a1 + a2;
    }

    @Override
    public void proc3(String a1, int a2, int a3) {
      procResult = a1 + a2 + a3;
    }

    @Override
    public void proc4(String a1, int a2, int a3, int a4) {
      procResult = a1 + a2 + a3 + a4;
    }

    @Override
    public void proc5(String a1, int a2, int a3, int a4, int a5) {
      procResult = a1 + a2 + a3 + a4 + a5;
    }

    @Override
    public void proc6(String a1, int a2, int a3, int a4, int a5, int a6) {
      procResult = a1 + a2 + a3 + a4 + a5 + a6;
    }

    @Override
    public String query() {
      return procResult;
    }

    @Override
    public String update() {
      return "update";
    }

    @Override
    public String update1(String a1) {
      return a1;
    }

    @Override
    public String update2(String a1, int a2) {
      return a1 + a2;
    }

    @Override
    public String update3(String a1, int a2, int a3) {
      return a1 + a2 + a3;
    }

    @Override
    public String update4(String a1, int a2, int a3, int a4) {
      return a1 + a2 + a3 + a4;
    }

    @Override
    public String update5(String a1, int a2, int a3, int a4, int a5) {
      return a1 + a2 + a3 + a4 + a5;
    }

    @Override
    public String update6(String a1, int a2, int a3, int a4, int a5, int a6) {
      return a1 + a2 + a3 + a4 + a5 + a6;
    }
  }
}
