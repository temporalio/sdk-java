package io.temporal.workflow.shared;

import io.temporal.workflow.*;

public class TestMultiArgWorkflowUpdateFunctions {

  @WorkflowInterface
  public interface TestMultiArgUpdateWorkflow
      extends TestNoArgsUpdateFunc,
          Test1ArgUpdateFunc,
          Test2ArgUpdateFunc,
          Test3ArgUpdateFunc,
          Test4ArgUpdateFunc,
          Test5ArgUpdateFunc,
          Test6ArgUpdateFunc,
          TestNoArgsUpdateProc,
          Test1ArgUpdateProc,
          Test2ArgUpdateProc,
          Test3ArgUpdateProc,
          Test4ArgUpdateProc,
          Test5ArgUpdateProc,
          Test6ArgUpdateProc {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void complete();
  }

  public interface TestNoArgsUpdateFunc {
    @UpdateMethod
    String func();
  }

  public interface Test1ArgUpdateFunc {
    @UpdateMethod
    String func1(String input);
  }

  public interface Test2ArgUpdateFunc {

    @UpdateMethod
    String func2(String a1, int a2);
  }

  public interface Test3ArgUpdateFunc {

    @UpdateMethod
    String func3(String a1, int a2, int a3);
  }

  public interface Test4ArgUpdateFunc {

    @UpdateMethod
    String func4(String a1, int a2, int a3, int a4);
  }

  public interface Test5ArgUpdateFunc {

    @UpdateMethod
    String func5(String a1, int a2, int a3, int a4, int a5);
  }

  public interface Test6ArgUpdateFunc {

    @UpdateMethod
    String func6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  public interface ProcInvocationQueryable {

    @QueryMethod(name = "getTrace")
    String query();
  }

  public interface TestNoArgsUpdateProc {

    @UpdateMethod
    void proc();
  }

  public interface Test1ArgUpdateProc {

    @UpdateMethod
    void proc1(String input);
  }

  public interface Test2ArgUpdateProc {

    @UpdateMethod
    void proc2(String a1, int a2);
  }

  public interface Test3ArgUpdateProc {

    @UpdateMethod
    void proc3(String a1, int a2, int a3);
  }

  public interface Test4ArgUpdateProc {

    @UpdateMethod
    void proc4(String a1, int a2, int a3, int a4);
  }

  public interface Test5ArgUpdateProc {

    @UpdateMethod
    void proc5(String a1, int a2, int a3, int a4, int a5);
  }

  public interface Test6ArgUpdateProc {

    @UpdateMethod
    void proc6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  public static class TestMultiArgUpdateWorkflowImpl implements TestMultiArgUpdateWorkflow {

    private String procResult = "";
    private boolean signaled;

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
      procResult += "proc";
    }

    @Override
    public void proc1(String a1) {
      procResult += a1;
    }

    @Override
    public void proc2(String a1, int a2) {
      procResult += a1 + a2;
    }

    @Override
    public void proc3(String a1, int a2, int a3) {
      procResult += a1 + a2 + a3;
    }

    @Override
    public void proc4(String a1, int a2, int a3, int a4) {
      procResult += a1 + a2 + a3 + a4;
    }

    @Override
    public void proc5(String a1, int a2, int a3, int a4, int a5) {
      procResult += a1 + a2 + a3 + a4 + a5;
    }

    @Override
    public void proc6(String a1, int a2, int a3, int a4, int a5, int a6) {
      procResult += a1 + a2 + a3 + a4 + a5 + a6;
    }

    @Override
    public String execute() {
      Workflow.await(() -> signaled);
      return procResult;
    }

    @Override
    public void complete() {
      signaled = true;
    }
  }
}
