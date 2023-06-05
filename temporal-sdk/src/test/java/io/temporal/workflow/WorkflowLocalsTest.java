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
import static org.junit.Assert.assertNull;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;

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

        private final WorkflowThreadLocal<Integer> threadLocal =
                WorkflowThreadLocal.withInitial(() -> 2);

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

    public static class TestWorkflowLocalsNullIsNotAbsent implements TestWorkflow1 {

        private final AtomicInteger localCalls = new AtomicInteger(0);
        private final WorkflowLocal<Integer> workflowLocal =
                WorkflowLocal.withInitial(
                        () -> {
                            localCalls.addAndGet(1);
                            return null;
                        });

        @Override
        public String execute(String taskQueue) {
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
    public SDKTestWorkflowRule testWorkflowRuleNullIsNotAbsent =
            SDKTestWorkflowRule.newBuilder()
                    .setWorkflowTypes(TestWorkflowLocalsNullIsNotAbsent.class)
                    .build();

    @Test
    public void testWorkflowLocalsNullIsNotAbsent() {
        TestWorkflow1 workflowStub =
                testWorkflowRuleNullIsNotAbsent.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
        String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
        Assert.assertEquals("ok", result);
    }
}
