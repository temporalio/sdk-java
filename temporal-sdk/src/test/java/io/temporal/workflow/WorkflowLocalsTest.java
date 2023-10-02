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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowLocalsTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowLocals.class).build();

  @Test
  public void testWorkflowLocals() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("result=2, 100", result);
  }

  public static class TestWorkflowLocals implements TestWorkflow1 {

    @SuppressWarnings("deprecation")
    private final WorkflowThreadLocal<Integer> threadLocal =
        WorkflowThreadLocal.withInitial(() -> 2);

    @SuppressWarnings("deprecation")
    private final WorkflowLocal<Integer> workflowLocal = WorkflowLocal.withInitial(() -> 5);

    @Override
    public String execute(String taskQueue) {
      assertEquals(2, (int) threadLocal.get());
      assertEquals(5, (int) workflowLocal.get());
      Promise<Void> p1 =
          Async.procedure(
              () -> {
                assertEquals(2, (int) threadLocal.get());
                threadLocal.set(10);
                Workflow.sleep(Duration.ofSeconds(1));
                assertEquals(10, (int) threadLocal.get());
                assertEquals(100, (int) workflowLocal.get());
              });
      Promise<Void> p2 =
          Async.procedure(
              () -> {
                assertEquals(2, (int) threadLocal.get());
                threadLocal.set(22);
                workflowLocal.set(100);
                assertEquals(22, (int) threadLocal.get());
              });
      p1.get();
      p2.get();
      return "result=" + threadLocal.get() + ", " + workflowLocal.get();
    }
  }

  public static class TestWorkflowLocalsSupplierReuse implements TestWorkflow1 {

    private final AtomicInteger localCalls = new AtomicInteger(0);
    private final AtomicInteger threadLocalCalls = new AtomicInteger(0);

    @SuppressWarnings("deprecation")
    private final WorkflowThreadLocal<Integer> workflowThreadLocal =
        WorkflowThreadLocal.withInitial(
            () -> {
              threadLocalCalls.addAndGet(1);
              return null;
            });

    @SuppressWarnings("deprecation")
    private final WorkflowLocal<Integer> workflowLocal =
        WorkflowLocal.withInitial(
            () -> {
              localCalls.addAndGet(1);
              return null;
            });

    @Override
    public String execute(String taskQueue) {
      assertNull(workflowThreadLocal.get());
      workflowThreadLocal.set(null);
      assertNull(workflowThreadLocal.get());
      assertNull(workflowThreadLocal.get());
      workflowThreadLocal.set(55);
      assertEquals((long) workflowThreadLocal.get(), 55);
      assertEquals(threadLocalCalls.get(), 1);

      assertNull(workflowLocal.get());
      workflowLocal.set(null);
      assertNull(workflowLocal.get());
      assertNull(workflowLocal.get());
      workflowLocal.set(58);
      assertEquals((long) workflowLocal.get(), 58);
      assertEquals(localCalls.get(), 1);
      return "ok";
    }
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleSupplierReuse =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowLocalsSupplierReuse.class)
          .build();

  @Test
  public void testWorkflowLocalsSupplierReuse() {
    TestWorkflow1 workflowStub =
        testWorkflowRuleSupplierReuse.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("ok", result);
  }

  @SuppressWarnings("deprecation")
  static final WorkflowThreadLocal<AtomicInteger> threadLocal =
      WorkflowThreadLocal.withInitial(() -> new AtomicInteger(2));

  @SuppressWarnings("deprecation")
  static final WorkflowLocal<AtomicInteger> workflowLocal =
      WorkflowLocal.withInitial(() -> new AtomicInteger(5));

  static final WorkflowThreadLocal<AtomicInteger> threadLocalCached =
      WorkflowThreadLocal.withCachedInitial(() -> new AtomicInteger(2));

  static final WorkflowLocal<AtomicInteger> workflowLocalCached =
      WorkflowLocal.withCachedInitial(() -> new AtomicInteger(5));

  public static class TestInit implements TestWorkflowReturnString {

    @Override
    public String execute() {
      assertEquals(2, threadLocal.get().getAndSet(3));
      assertEquals(5, workflowLocal.get().getAndSet(6));
      assertEquals(2, threadLocalCached.get().getAndSet(3));
      assertEquals(5, workflowLocalCached.get().getAndSet(6));
      String out = Workflow.newChildWorkflowStub(TestWorkflow1.class).execute("ign");
      assertEquals("ok", out);
      return "result="
          + threadLocal.get().get()
          + ", "
          + workflowLocal.get().get()
          + ", "
          + threadLocalCached.get().get()
          + ", "
          + workflowLocalCached.get().get();
    }
  }

  public static class TestChildInit implements TestWorkflow1 {

    @Override
    public String execute(String arg1) {
      assertEquals(2, threadLocal.get().getAndSet(8));
      assertEquals(5, workflowLocal.get().getAndSet(0));
      return "ok";
    }
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleInitialValueNotShared =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestInit.class, TestChildInit.class)
          .build();

  @Test
  public void testWorkflowInitialNotShared() {
    TestWorkflowReturnString workflowStub =
        testWorkflowRuleInitialValueNotShared.newWorkflowStubTimeoutOptions(
            TestWorkflowReturnString.class);
    String result = workflowStub.execute();
    Assert.assertEquals("result=2, 5, 3, 6", result);
  }

  public static class TestCaching implements TestWorkflow1 {

    @Override
    public String execute(String arg1) {
      assertNotSame(threadLocal.get(), threadLocal.get());
      assertNotSame(workflowLocal.get(), workflowLocal.get());
      threadLocal.set(threadLocal.get());
      workflowLocal.set(workflowLocal.get());
      assertSame(threadLocal.get(), threadLocal.get());
      assertSame(workflowLocal.get(), workflowLocal.get());

      assertSame(threadLocalCached.get(), threadLocalCached.get());
      assertSame(workflowLocalCached.get(), workflowLocalCached.get());
      return "ok";
    }
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleCaching =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestCaching.class).build();

  @Test
  public void testWorkflowLocalCaching() {
    TestWorkflow1 workflowStub =
        testWorkflowRuleCaching.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String out = workflowStub.execute("ign");
    assertEquals("ok", out);
  }
}
