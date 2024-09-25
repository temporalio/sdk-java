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

package io.temporal.workflow.shared;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

public class TestMultiArgWorkflowFunctions {

  public interface TestUpdateFunc {

    @UpdateMethod
    String update(Integer i);
  }

  @WorkflowInterface
  public interface TestNoArgsWorkflowFunc extends TestUpdateFunc {

    @WorkflowMethod
    String func();
  }

  @WorkflowInterface
  public interface Test1ArgWorkflowFunc extends TestUpdateFunc {

    @WorkflowMethod(name = "func1")
    int func1(int input);
  }

  @WorkflowInterface
  public interface Test2ArgWorkflowFunc extends TestUpdateFunc {

    @WorkflowMethod
    String func2(String a1, int a2);
  }

  @WorkflowInterface
  public interface Test3ArgWorkflowFunc extends TestUpdateFunc {

    @WorkflowMethod
    String func3(String a1, int a2, int a3);
  }

  @WorkflowInterface
  public interface Test4ArgWorkflowFunc extends TestUpdateFunc {

    @WorkflowMethod
    String func4(String a1, int a2, int a3, int a4);
  }

  @WorkflowInterface
  public interface Test5ArgWorkflowFunc extends TestUpdateFunc {

    @WorkflowMethod
    String func5(String a1, int a2, int a3, int a4, int a5);
  }

  @WorkflowInterface
  public interface Test6ArgWorkflowFunc extends TestUpdateFunc {

    @WorkflowMethod
    String func6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  public interface ProcInvocationQueryable {

    @QueryMethod(name = "getTrace")
    String query();
  }

  @WorkflowInterface
  public interface TestNoArgsWorkflowProc extends ProcInvocationQueryable, TestUpdateFunc {

    @WorkflowMethod
    void proc();
  }

  @WorkflowInterface
  public interface Test1ArgWorkflowProc extends ProcInvocationQueryable, TestUpdateFunc {

    @WorkflowMethod
    void proc1(String input);
  }

  @WorkflowInterface
  public interface Test2ArgWorkflowProc extends ProcInvocationQueryable, TestUpdateFunc {

    @WorkflowMethod
    void proc2(String a1, int a2);
  }

  @WorkflowInterface
  public interface Test3ArgWorkflowProc extends ProcInvocationQueryable, TestUpdateFunc {

    @WorkflowMethod
    void proc3(String a1, int a2, int a3);
  }

  @WorkflowInterface
  public interface Test4ArgWorkflowProc extends ProcInvocationQueryable, TestUpdateFunc {

    @WorkflowMethod
    void proc4(String a1, int a2, int a3, int a4);
  }

  @WorkflowInterface
  public interface Test5ArgWorkflowProc extends ProcInvocationQueryable, TestUpdateFunc {

    @WorkflowMethod
    void proc5(String a1, int a2, int a3, int a4, int a5);
  }

  @WorkflowInterface
  public interface Test6ArgWorkflowProc extends ProcInvocationQueryable, TestUpdateFunc {

    @WorkflowMethod
    void proc6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  public static class TestMultiArgWorkflowImpl
      implements TestUpdateFunc,
          TestNoArgsWorkflowFunc,
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
    public int func1(int a1) {
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
    public String update(Integer i) {
      return i.toString();
    }
  }
}
