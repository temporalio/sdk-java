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

package com.uber.cadence.workflow;

import com.uber.cadence.SignalExternalWorkflowExecutionFailedCause;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.activity.DoNotCompleteOnReturn;
import com.uber.cadence.activity.MethodRetry;
import com.uber.cadence.client.ActivityCancelledException;
import com.uber.cadence.client.ActivityCompletionClient;
import com.uber.cadence.client.ActivityNotExistsException;
import com.uber.cadence.client.DuplicateWorkflowException;
import com.uber.cadence.client.UntypedWorkflowStub;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowException;
import com.uber.cadence.client.WorkflowFailureException;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.sync.DeterministicRunnerTest;
import com.uber.cadence.worker.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class WorkflowTest {

    @Rule
    public TestName testName = new TestName();

    private static final String domain = "UnitTest";
    private static final Logger log = LoggerFactory.getLogger(WorkflowTest.class);
    private static String taskList;

    static {

    }

    private Worker worker;
    private TestActivitiesImpl activitiesImpl;
    private WorkflowClient workflowClient;
    private WorkflowClient workflowClientWithOptions;

    private static WorkflowOptions.Builder newWorkflowOptionsBuilder() {
        return new WorkflowOptions.Builder()
                .setExecutionStartToCloseTimeout(Duration.ofSeconds(10))
                .setTaskList(taskList);
    }

    private static ActivityOptions newActivityOptions1() {
        return new ActivityOptions.Builder()
                .setTaskList(taskList)
                .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                .setHeartbeatTimeout(Duration.ofSeconds(5))
                .setScheduleToStartTimeout(Duration.ofSeconds(5))
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static ActivityOptions newActivityOptions2() {
        return new ActivityOptions.Builder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(20))
                .build();
    }


    @Before
    public void setUp() {
        taskList = "WorkflowTest-" + testName.getMethodName();
        // TODO: Make this configuratble instead of always using local instance.
        worker = new Worker(domain, taskList);
        workflowClient = WorkflowClient.newInstance(domain);
        ActivityCompletionClient completionClient = workflowClient.newActivityCompletionClient();
        activitiesImpl = new TestActivitiesImpl(completionClient);
        worker.registerActivitiesImplementations(activitiesImpl);
        WorkflowClientOptions clientOptions = new WorkflowClientOptions.Builder()
                .setDataConverter(JsonDataConverter.getInstance())
                .build();
        workflowClientWithOptions = WorkflowClient.newInstance(domain, clientOptions);
        newWorkflowOptionsBuilder();
        newActivityOptions1();
        activitiesImpl.invocations.clear();
        activitiesImpl.procResult.clear();
    }

    @After
    public void tearDown() {
        worker.shutdown(Duration.ofMillis(1));
        activitiesImpl.close();
    }

    private void startWorkerFor(Class<?>... workflowTypes) {
        worker.registerWorkflowImplementationTypes(workflowTypes);
        worker.start();
    }

    public interface TestWorkflow1 {
        @WorkflowMethod
        String execute();
    }

    public interface TestWorkflowSignaled {
        @WorkflowMethod
        String execute();

        @SignalMethod(name = "testSignal")
        void signal1(String arg);
    }

    public interface TestWorkflow2 {
        @WorkflowMethod(name = "testActivity")
        String execute();
    }

    public static class TestSyncWorkflowImpl implements TestWorkflow1 {

        @Override
        public String execute() {
            TestActivities activities = Workflow.newActivityStub(TestActivities.class, newActivityOptions1());
            // Invoke synchronously in a separate thread for testing purposes only.
            // In real workflows use
            // Async.invoke(activities::activityWithDelay, 1000, true)
            Promise<String> a1 = Async.invoke(() -> activities.activityWithDelay(1000, true));
            Workflow.sleep(2000);
            return activities.activity2(a1.get(), 10);
        }
    }

    @Test
    public void testSync() {
        startWorkerFor(TestSyncWorkflowImpl.class);
        TestWorkflow1 workflowStub = workflowClient.newWorkflowStub(TestWorkflow1.class, newWorkflowOptionsBuilder().build());
        String result = workflowStub.execute();
        assertEquals("activity10", result);
    }

    public static class TestActivityRetry implements TestWorkflow1 {

        private final TestActivities activities;

        public TestActivityRetry() {
            ActivityOptions options = new ActivityOptions.Builder()
                    .setTaskList(taskList)
                    .setHeartbeatTimeout(Duration.ofSeconds(5))
                    .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                    .setScheduleToStartTimeout(Duration.ofSeconds(5))
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(new RetryOptions.Builder()
                            .setMinimumAttempts(2)
                            .setMaximumInterval(Duration.ofSeconds(1))
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumAttempts(3)
                            .build())
                    .build();
            this.activities = Workflow.newActivityStub(TestActivities.class, options);
        }

        @Override
        public String execute() {
            activities.throwIO();
            return "ignored";
        }
    }

    @Test
    public void testActivityRetry() {
        startWorkerFor(TestActivityRetry.class);
        TestWorkflow1 workflowStub = workflowClient.newWorkflowStub(TestWorkflow1.class, newWorkflowOptionsBuilder().build());
        try {
            workflowStub.execute();
            fail("unreachable");
        } catch (WorkflowException e) {
            assertTrue(e.getCause().getCause() instanceof IOException);
        }
        assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
    }

    public static class TestActivityRetryAnnotated implements TestWorkflow1 {

        private final TestActivities activities;

        public TestActivityRetryAnnotated() {
            this.activities = Workflow.newActivityStub(TestActivities.class);
        }

        @Override
        public String execute() {
            activities.throwIOAnnotated();
            return "ignored";
        }
    }

    @Test
    public void testActivityRetryAnnotated() {
        startWorkerFor(TestActivityRetryAnnotated.class);
        TestWorkflow1 workflowStub = workflowClient.newWorkflowStub(TestWorkflow1.class, newWorkflowOptionsBuilder().build());
        try {
            workflowStub.execute();
            fail("unreachable");
        } catch (WorkflowException e) {
            e.printStackTrace();
            assertTrue(e.getCause().getCause() instanceof IOException);
        }
        assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
    }

    public static class TestAsyncActivityRetry implements TestWorkflow1 {

        private final TestActivities activities;

        public TestAsyncActivityRetry() {
            ActivityOptions options = new ActivityOptions.Builder()
                    .setTaskList(taskList)
                    .setHeartbeatTimeout(Duration.ofSeconds(5))
                    .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                    .setScheduleToStartTimeout(Duration.ofSeconds(5))
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(new RetryOptions.Builder()
                            .setMinimumAttempts(2)
                            .setMaximumInterval(Duration.ofSeconds(1))
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumAttempts(3)
                            .build())
                    .build();
            this.activities = Workflow.newActivityStub(TestActivities.class, options);
        }

        @Override
        public String execute() {
            Async.invoke(activities::throwIO).get();
            return "ignored";
        }
    }

    @Test
    public void testAsyncActivityRetry() {
        startWorkerFor(TestAsyncActivityRetry.class);
        TestWorkflow1 workflowStub = workflowClient.newWorkflowStub(TestWorkflow1.class, newWorkflowOptionsBuilder().build());
        try {
            workflowStub.execute();
            fail("unreachable");
        } catch (WorkflowException e) {
            assertTrue(e.getCause().getCause() instanceof IOException);
        }
        assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
    }


    public static class TestHeartbeatTimeoutDetails implements TestWorkflow1 {

        @Override
        public String execute() {
            ActivityOptions options = new ActivityOptions.Builder()
                    .setTaskList(taskList)
                    .setHeartbeatTimeout(Duration.ofSeconds(1)) // short heartbeat timeout;
                    .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                    .build();

            TestActivities activities = Workflow.newActivityStub(TestActivities.class, options);
            try {
                // false for second argument means to heartbeat once to set details and then stop.
                activities.activityWithDelay(5000, false);
            } catch (ActivityTimeoutException e) {
                assertEquals(TimeoutType.HEARTBEAT, e.getTimeoutType());
                return e.getDetails(String.class);
            }
            throw new RuntimeException("unreachable");
        }
    }

    @Test
    public void testHeartbeatTimeoutDetails() {
        startWorkerFor(TestHeartbeatTimeoutDetails.class);
        TestWorkflow1 workflowStub = workflowClient.newWorkflowStub(TestWorkflow1.class, newWorkflowOptionsBuilder().build());
        String result = workflowStub.execute();
        assertEquals("heartbeatValue", result);
    }

    @Test
    public void testSyncUntypedAndStackTrace() throws InterruptedException {
        startWorkerFor(TestSyncWorkflowImpl.class);
        UntypedWorkflowStub workflowStub = workflowClient.newUntypedWorkflowStub("TestWorkflow1::execute",
                newWorkflowOptionsBuilder().build());
        WorkflowExecution execution = workflowStub.start();
        Thread.sleep(500);
        String stackTrace = workflowStub.query(WorkflowClient.QUERY_TYPE_STACK_TRCE, String.class);
        assertTrue(stackTrace, stackTrace.contains("WorkflowTest$TestSyncWorkflowImpl.execute"));
        assertTrue(stackTrace, stackTrace.contains("activityWithDelay"));
        // Test stub created from workflow execution.
        workflowStub = workflowClient.newUntypedWorkflowStub(execution);
        stackTrace = workflowStub.query(WorkflowClient.QUERY_TYPE_STACK_TRCE, String.class);
        assertTrue(stackTrace, stackTrace.contains("WorkflowTest$TestSyncWorkflowImpl.execute"));
        assertTrue(stackTrace, stackTrace.contains("activityWithDelay"));
        String result = workflowStub.getResult(String.class);
        assertEquals("activity10", result);
    }

    @Test
    public void testWorkflowCancellation() {
        startWorkerFor(TestSyncWorkflowImpl.class);
        UntypedWorkflowStub client = workflowClient.newUntypedWorkflowStub("TestWorkflow1::execute",
                newWorkflowOptionsBuilder().build());
        client.start();
        client.cancel();
        try {
            client.getResult(String.class);
            fail("unreachable");
        } catch (CancellationException ignored) {
        }
    }

    public static class TestDetachedCancellationScope implements TestWorkflow1 {

        @Override
        public String execute() {
            TestActivities testActivities = Workflow.newActivityStub(TestActivities.class, newActivityOptions1());
            try {
                testActivities.activityWithDelay(100000, true);
                fail("unreachable");
            } catch (CancellationException e) {
                Workflow.newDetachedCancellationScope(() -> assertEquals("a1", testActivities.activity1("a1")));
            }
            try {
                Workflow.sleep(Duration.ofHours(1));
                fail("unreachable");
            } catch (CancellationException e) {
                Workflow.newDetachedCancellationScope(() -> assertEquals("a12", testActivities.activity2("a1", 2)));
            }
            try {
                Workflow.newTimer(Duration.ofHours(1)).get();
                fail("unreachable");
            } catch (CancellationException e) {
                Workflow.newDetachedCancellationScope(() -> assertEquals("a123", testActivities.activity3("a1", 2, 3)));
            }
            return "result";
        }
    }

    @Test
    public void testDetachedScope() throws InterruptedException {
        startWorkerFor(TestDetachedCancellationScope.class);
        UntypedWorkflowStub client = workflowClient.newUntypedWorkflowStub("TestWorkflow1::execute",
                newWorkflowOptionsBuilder().build());
        client.start();
        Thread.sleep(500); // To let activityWithDelay start.
        client.cancel();
        try {
            client.getResult(String.class);
            fail("unreachable");
        } catch (CancellationException ignored) {
        }
        activitiesImpl.assertInvocations("activityWithDelay", "activity1", "activity2", "activity3");
    }

    public interface TestContinueAsNew {
        @WorkflowMethod
        int execute(int count);
    }

    public static class TestContinueAsNewImpl implements TestContinueAsNew {

        @Override
        public int execute(int count) {
            if (count == 0) {
                return 111;
            }
            TestContinueAsNew next = Workflow.newContinueAsNewStub(TestContinueAsNew.class, null);
            next.execute(count - 1);
            throw new RuntimeException("unreachable");
        }
    }

    @Test
    public void testContinueAsNew() {
        startWorkerFor(TestContinueAsNewImpl.class);
        TestContinueAsNew client = workflowClient.newWorkflowStub(TestContinueAsNew.class, newWorkflowOptionsBuilder().build());
        int result = client.execute(4);
        assertEquals(111, result);
    }

    public static class TestAsyncActivityWorkflowImpl implements TestWorkflow1 {

        @Override
        public String execute() {
            TestActivities testActivities = Workflow.newActivityStub(TestActivities.class, newActivityOptions2());
            Promise<String> a = Async.invoke(testActivities::activity);
            Promise<String> a1 = Async.invoke(testActivities::activity1, "1");
            Promise<String> a2 = Async.invoke(testActivities::activity2, "1", 2);
            Promise<String> a3 = Async.invoke(testActivities::activity3, "1", 2, 3);
            Promise<String> a4 = Async.invoke(testActivities::activity4, "1", 2, 3, 4);
            Promise<String> a5 = Async.invoke(testActivities::activity5, "1", 2, 3, 4, 5);
            Promise<String> a6 = Async.invoke(testActivities::activity6, "1", 2, 3, 4, 5, 6);
            assertEquals("activity", a.get());
            assertEquals("1", a1.get());
            assertEquals("12", a2.get());
            assertEquals("123", a3.get());
            assertEquals("1234", a4.get());
            assertEquals("12345", a5.get());
            assertEquals("123456", a6.get());

            Async.invoke(testActivities::proc).get();
            Async.invoke(testActivities::proc1, "1").get();
            Async.invoke(testActivities::proc2, "1", 2).get();
            Async.invoke(testActivities::proc3, "1", 2, 3).get();
            Async.invoke(testActivities::proc4, "1", 2, 3, 4).get();
            Async.invoke(testActivities::proc5, "1", 2, 3, 4, 5).get();
            Async.invoke(testActivities::proc6, "1", 2, 3, 4, 5, 6).get();
            return "workflow";
        }
    }

    @Test
    public void testAsyncActivity() {
        startWorkerFor(TestAsyncActivityWorkflowImpl.class);
        TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, newWorkflowOptionsBuilder().build());
        String result = client.execute();
        assertEquals("workflow", result);
        assertEquals("proc", activitiesImpl.procResult.get(0));
        assertEquals("1", activitiesImpl.procResult.get(1));
        assertEquals("12", activitiesImpl.procResult.get(2));
        assertEquals("123", activitiesImpl.procResult.get(3));
        assertEquals("1234", activitiesImpl.procResult.get(4));
        assertEquals("12345", activitiesImpl.procResult.get(5));
        assertEquals("123456", activitiesImpl.procResult.get(6));
    }

    private void assertResult(String expected, WorkflowExecution execution) {
        String result = workflowClient.newUntypedWorkflowStub(execution).getResult(String.class);
        assertEquals(expected, result);
    }

    private void waitForProc(WorkflowExecution execution) {
        workflowClient.newUntypedWorkflowStub(execution).getResult(Void.class);
    }

    @Test
    public void testAsyncStart() {
        startWorkerFor(TestMultiargsWorkflowsImpl.class);
        TestMultiargsWorkflows stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        assertResult("func", WorkflowClient.asyncStart(stub::func));
        assertEquals("func", stub.func()); // Check that duplicated start just returns the result.
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class);
        assertResult("1", WorkflowClient.asyncStart(stub::func1, "1"));
        assertEquals("1", stub.func1("1")); // Check that duplicated start just returns the result.
        // Check that duplicated start is not allowed for AllowDuplicate IdReusePolicy
        stub = workflowClientWithOptions.newWorkflowStub(TestMultiargsWorkflows.class,
                newWorkflowOptionsBuilder().setWorkflowIdReusePolicy(WorkflowIdReusePolicy.AllowDuplicate).build());
        assertResult("12", WorkflowClient.asyncStart(stub::func2, "1", 2));
        try {
            stub.func2("1", 2);
            fail("unreachable");
        } catch (DuplicateWorkflowException e) {
            // expected
        }
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        assertResult("123", WorkflowClient.asyncStart(stub::func3, "1", 2, 3));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        assertResult("1234", WorkflowClient.asyncStart(stub::func4, "1", 2, 3, 4));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        assertResult("12345", WorkflowClient.asyncStart(stub::func5, "1", 2, 3, 4, 5));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        assertResult("123456", WorkflowClient.asyncStart(stub::func6, "1", 2, 3, 4, 5, 6));

        stub = workflowClientWithOptions.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        waitForProc(WorkflowClient.asyncStart(stub::proc));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        waitForProc(WorkflowClient.asyncStart(stub::proc1, "1"));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        waitForProc(WorkflowClient.asyncStart(stub::proc2, "1", 2));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        waitForProc(WorkflowClient.asyncStart(stub::proc3, "1", 2, 3));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        waitForProc(WorkflowClient.asyncStart(stub::proc4, "1", 2, 3, 4));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        waitForProc(WorkflowClient.asyncStart(stub::proc5, "1", 2, 3, 4, 5));
        stub = workflowClient.newWorkflowStub(TestMultiargsWorkflows.class, newWorkflowOptionsBuilder().build());
        waitForProc(WorkflowClient.asyncStart(stub::proc6, "1", 2, 3, 4, 5, 6));
        assertEquals("proc", TestMultiargsWorkflowsImpl.procResult.get(0));
        assertEquals("1", TestMultiargsWorkflowsImpl.procResult.get(1));
        assertEquals("12", TestMultiargsWorkflowsImpl.procResult.get(2));
        assertEquals("123", TestMultiargsWorkflowsImpl.procResult.get(3));
        assertEquals("1234", TestMultiargsWorkflowsImpl.procResult.get(4));
        assertEquals("12345", TestMultiargsWorkflowsImpl.procResult.get(5));
        assertEquals("123456", TestMultiargsWorkflowsImpl.procResult.get(6));
    }

    public static class TestTimerWorkflowImpl implements TestWorkflow2 {

        @Override
        public String execute() {
            Promise<Void> timer1 = Workflow.newTimer(Duration.ofMillis(700));
            Promise<Void> timer2 = Workflow.newTimer(Duration.ofMillis(1300));

            long time = Workflow.currentTimeMillis();
            timer1.thenApply((r) -> {
                // Testing that timer can be created from a callback thread.
                Workflow.newTimer(Duration.ofSeconds(10));
                Workflow.currentTimeMillis(); // Testing that time is available here.
                return r;
            }).get();
            timer1.get();
            long slept = Workflow.currentTimeMillis() - time;
            // Also checks that rounding up to a second works.
            assertTrue(String.valueOf(slept), slept > 1000);
            timer2.get();
            slept = Workflow.currentTimeMillis() - time;
            assertTrue(String.valueOf(slept), slept > 2000);
            return "testTimer";
        }
    }

    @Test
    public void testTimer() {
        startWorkerFor(TestTimerWorkflowImpl.class);
        TestWorkflow2 client = workflowClient.newWorkflowStub(TestWorkflow2.class, newWorkflowOptionsBuilder().build());
        String result = client.execute();
        assertEquals("testTimer", result);
    }

    private static final RetryOptions retryOptions = new RetryOptions.Builder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumInterval(Duration.ofSeconds(1))
            .setExpiration(Duration.ofSeconds(2))
            .setBackoffCoefficient(1)
            .build();

    public static class TestAsyncRetryWorkflowImpl implements TestWorkflow2 {

        static List<String> trace = new ArrayList<>();

        @Override
        public String execute() {
            trace.clear(); // clear because of replay
            trace.add("started");
            Async.retry(retryOptions, () -> {
                trace.add("retry at " + Workflow.currentTimeMillis());
                return Workflow.newFailedPromise(new IllegalThreadStateException("simulated"));
            }).get();
            trace.add("beforeSleep");
            Workflow.sleep(60000);
            trace.add("done");
            return "";
        }
    }

    /**
     * @see DeterministicRunnerTest#testRetry()
     */
    @Test
    public void testAsyncRetry() {
        startWorkerFor(TestAsyncRetryWorkflowImpl.class);
        TestWorkflow2 client = workflowClient.newWorkflowStub(TestWorkflow2.class, newWorkflowOptionsBuilder().build());
        String result = null;
        try {
            result = client.execute();
            fail("unreachable");
        } catch (WorkflowException e) {
            assertTrue(e.getCause() instanceof IllegalThreadStateException);
            assertEquals("simulated", e.getCause().getMessage());
        }
        assertNull(result);
        assertEquals(TestAsyncRetryWorkflowImpl.trace.toString(), 3, TestAsyncRetryWorkflowImpl.trace.size());
        assertEquals("started", TestAsyncRetryWorkflowImpl.trace.get(0));
        assertTrue(TestAsyncRetryWorkflowImpl.trace.get(1).startsWith("retry at "));
        assertTrue(TestAsyncRetryWorkflowImpl.trace.get(2).startsWith("retry at "));
    }


    public interface TestExceptionPropagation {
        @WorkflowMethod
        void execute();
    }

    public static class ThrowingChild implements TestWorkflow1 {

        @Override
        public String execute() {
            TestActivities testActivities = Workflow.newActivityStub(TestActivities.class, newActivityOptions2());
            try {
                testActivities.throwIO();
                fail("unreachable");
                return "ignored";
            } catch (ActivityFailureException e) {
                try {
                    assertTrue(e.getMessage().contains("TestActivities::throwIO"));
                    assertTrue(e.getCause() instanceof IOException);
                    assertEquals("simulated IO problem", e.getCause().getMessage());
                } catch (AssertionError ae) {
                    // Errors cause decision to fail. But we want workflow to fail in this case.
                    throw new RuntimeException(ae);
                }
                Throwable ee = new NumberFormatException();
                ee.initCause(e);
                throw Workflow.throwWrapped(ee);
            }
        }
    }

    public static class TestExceptionPropagationImpl implements TestExceptionPropagation {
        @Override
        public void execute() {
            ChildWorkflowOptions options = new ChildWorkflowOptions.Builder()
                    .setExecutionStartToCloseTimeout(Duration.ofHours(1)).build();
            TestWorkflow1 child = Workflow.newWorkflowStub(TestWorkflow1.class, options);
            try {
                child.execute();
                fail("unreachable");
            } catch (RuntimeException e) {
                try {
                    assertNoEmptyStacks(e);
                    assertTrue(e.getMessage().contains("TestWorkflow1::execute"));
                    assertTrue(e instanceof ChildWorkflowException);
                    assertTrue(e.getCause() instanceof NumberFormatException);
                    assertTrue(e.getCause().getCause() instanceof ActivityFailureException);
                    assertTrue(e.getCause().getCause().getCause() instanceof IOException);
                    assertEquals("simulated IO problem", e.getCause().getCause().getCause().getMessage());
                } catch (AssertionError ae) {
                    // Errors cause decision to fail. But we want workflow to fail in this case.
                    throw new RuntimeException(ae);
                }
                Throwable fnf = new FileNotFoundException();
                fnf.initCause(e);
                throw Workflow.throwWrapped(fnf);
            }
        }
    }

    private static void assertNoEmptyStacks(RuntimeException e) {
        // Check that there are no empty stacks
        Throwable c = e;
        while (c != null) {
            assertTrue(c.getStackTrace().length > 0);
            c = c.getCause();
        }
    }

    /**
     * Test that an NPE thrown in an activity executed from a child workflow results in the following chain
     * of exceptions when an exception is received in an external client that executed workflow through a WorkflowClient:
     * <pre>
     * {@link WorkflowFailureException}
     *     ->{@link ChildWorkflowFailureException}
     *         ->{@link ActivityFailureException}
     *             ->OriginalActivityException
     * </pre>
     * This test also tests that Checked exception wrapping and unwrapping works producing a nice
     * exception chain without the wrappers.
     */
    @Test
    public void testExceptionPropagation() {
        startWorkerFor(ThrowingChild.class, TestExceptionPropagationImpl.class);
        TestExceptionPropagation client = workflowClient.newWorkflowStub(TestExceptionPropagation.class,
                newWorkflowOptionsBuilder().build());
        try {
            client.execute();
            fail("Unreachable");
        } catch (WorkflowFailureException e) {
            // Rethrow the assertion failure
            if (e.getCause().getCause() instanceof AssertionError) {
                throw (AssertionError) e.getCause().getCause();
            }
            assertNoEmptyStacks(e);
            // Uncomment to see the actual trace.
//            e.printStackTrace();
            assertTrue(e.getMessage(), e.getMessage().contains("TestExceptionPropagation::execute"));
            assertTrue(e.getStackTrace().length > 0);
            assertTrue(e.getCause() instanceof FileNotFoundException);
            assertTrue(e.getCause().getCause() instanceof ChildWorkflowException);
            assertTrue(e.getCause().getCause().getCause() instanceof NumberFormatException);
            assertTrue(e.getCause().getCause().getCause().getCause() instanceof ActivityFailureException);
            assertTrue(e.getCause().getCause().getCause().getCause().getCause() instanceof IOException);
            assertEquals("simulated IO problem", e.getCause().getCause().getCause().getCause().getCause().getMessage());
        }
    }


    public interface QueryableWorkflow {
        @WorkflowMethod
        String execute();

        @QueryMethod
        String getState();

        @SignalMethod(name = "testSignal")
        void mySignal(String value);
    }

    public static class TestSignalWorkflowImpl implements QueryableWorkflow {

        String state = "initial";
        List<String> signals = new ArrayList<>();
        CompletablePromise<Void> promise = Workflow.newPromise();

        @Override
        public String execute() {
            promise.get();
            return signals.get(0) + signals.get(1);
        }

        @Override
        public String getState() {
            return state;
        }

        @Override
        public void mySignal(String value) {
            log.info("TestSignalWorkflowImpl.mySignal value=" + value);
            state = value;
            signals.add(value);
            if (signals.size() == 2) {
                promise.complete(null);
            }
        }
    }

    @Test
    public void testSignal() throws Exception {
        startWorkerFor(TestSignalWorkflowImpl.class);
        QueryableWorkflow client = workflowClient.newWorkflowStub(QueryableWorkflow.class, newWorkflowOptionsBuilder().build());
        // To execute workflow client.execute() would do. But we want to start workflow and immediately return.
        WorkflowExecution execution = WorkflowClient.asyncStart(client::execute);
        assertEquals("initial", client.getState());
        client.mySignal("Hello ");
        Thread.sleep(200);

        // Test client created using WorkflowExecution
        client = workflowClient.newWorkflowStub(QueryableWorkflow.class, execution);
        assertEquals("Hello ", client.getState());

        // Test query through replay by a local worker.
        Worker queryWorker = new Worker(domain, taskList);
        queryWorker.registerWorkflowImplementationTypes(TestSignalWorkflowImpl.class);
        String queryResult = queryWorker.queryWorkflowExecution(execution, "QueryableWorkflow::getState", String.class);
        assertEquals("Hello ", queryResult);
        Thread.sleep(500);
        client.mySignal("World!");
        assertEquals("World!", client.getState());
        assertEquals("Hello World!", workflowClient.newUntypedWorkflowStub(execution).getResult(String.class));
    }

    @Test
    public void testSignalUntyped() {
        startWorkerFor(TestSignalWorkflowImpl.class);
        String workflowType = QueryableWorkflow.class.getSimpleName() + "::execute";
        UntypedWorkflowStub client = workflowClient.newUntypedWorkflowStub(workflowType, newWorkflowOptionsBuilder().build());
        // To execute workflow client.execute() would do. But we want to start workflow and immediately return.
        WorkflowExecution execution = client.start();
        assertEquals("initial", client.query("QueryableWorkflow::getState", String.class));
        client.signal("testSignal", "Hello ");
        assertEquals("Hello ", client.query("QueryableWorkflow::getState", String.class));
        client.signal("testSignal", "World!");
        assertEquals("World!", client.query("QueryableWorkflow::getState", String.class));
        assertEquals("Hello World!", workflowClient.newUntypedWorkflowStub(execution).getResult(String.class));
    }

    static final AtomicInteger decisionCount = new AtomicInteger();
    static final CompletableFuture<Boolean> sendSignal = new CompletableFuture<>();

    public static class TestSignalDuringLastDecisionWorkflowImpl implements TestWorkflowSignaled {

        private String signal;

        @Override
        public String execute() {
            if (decisionCount.incrementAndGet() == 1) {
                sendSignal.complete(true);
                // Never sleep in a real workflow using Thread.sleep.
                // Here it is to simulate a race condition.
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            return signal;
        }

        @Override
        public void signal1(String arg) {
            signal = arg;
        }
    }

    @Test
    public void testSignalDuringLastDecision() throws InterruptedException {
        startWorkerFor(TestSignalDuringLastDecisionWorkflowImpl.class);
        WorkflowOptions.Builder options = newWorkflowOptionsBuilder();
        options.setWorkflowId("testSignalDuringLastDecision-" + UUID.randomUUID().toString());
        TestWorkflowSignaled client = workflowClient.newWorkflowStub(TestWorkflowSignaled.class, options.build());
        WorkflowExecution execution = WorkflowClient.asyncStart(client::execute);
        try {
            sendSignal.get(2, TimeUnit.SECONDS);
            client.signal1("Signal Input");
        } catch (TimeoutException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        assertEquals("Signal Input", workflowClient.newUntypedWorkflowStub(execution).getResult(String.class));
    }

    public static class TestTimerCallbackBlockedWorkflowImpl implements TestWorkflow1 {

        @Override
        public String execute() {
            Promise<Void> timer1 = Workflow.newTimer(Duration.ZERO);
            Promise<Void> timer2 = Workflow.newTimer(Duration.ofSeconds(1));

            return timer1.thenApply((e) -> {
                timer2.get();
                return "timer2Fired";
            }).get();
        }
    }

    /**
     * Test that it is not allowed to block in the timer callback thread.
     */
    @Test
    public void testTimerCallbackBlocked() {
        startWorkerFor(TestTimerCallbackBlockedWorkflowImpl.class);
        WorkflowOptions.Builder options = new WorkflowOptions.Builder();
        options.setExecutionStartToCloseTimeout(Duration.ofSeconds(10));
        options.setTaskStartToCloseTimeout(Duration.ofSeconds(1));
        options.setTaskList(taskList);
        TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
        String result = client.execute();
        assertEquals("timer2Fired", result);
    }

    public interface ITestChild {
        @WorkflowMethod
        String execute(String arg);
    }

    public interface ITestNamedChild {
        @WorkflowMethod(name = "namedChild")
        String execute(String arg);
    }

    private static String child2Id = UUID.randomUUID().toString();

    public static class TestParentWorkflow implements TestWorkflow1 {

        private final ITestChild child1 = Workflow.newWorkflowStub(ITestChild.class);
        private final ITestNamedChild child2;

        public TestParentWorkflow() {
            ChildWorkflowOptions.Builder options = new ChildWorkflowOptions.Builder();
            options.setWorkflowId(child2Id);
            child2 = Workflow.newWorkflowStub(ITestNamedChild.class, options.build());
        }

        @Override
        public String execute() {
            Promise<String> r1 = Async.invoke(child1::execute, "Hello ");
            String r2 = child2.execute("World!");
            assertEquals(child2Id, Workflow.getChildWorkflowExecution(child2).get().getWorkflowId());
            return r1.get() + r2;
        }
    }

    public static class TestChild implements ITestChild {
        @Override
        public String execute(String arg) {
            return arg.toUpperCase();
        }
    }

    public static class TestNamedChild implements ITestNamedChild {
        @Override
        public String execute(String arg) {
            return arg.toUpperCase();
        }
    }

    @Test
    public void testChildWorkflow() {
        startWorkerFor(TestParentWorkflow.class, TestNamedChild.class, TestChild.class);

        WorkflowOptions.Builder options = new WorkflowOptions.Builder();
        options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
        options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
        options.setTaskList(taskList);
        TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
        assertEquals("HELLO WORLD!", client.execute());
    }

    public static class TestChildWorkflowRetryWorkflow implements TestWorkflow1 {

        private final ITestChild child;

        public TestChildWorkflowRetryWorkflow() {
            ChildWorkflowOptions options = new ChildWorkflowOptions.Builder()
                    .setExecutionStartToCloseTimeout(Duration.ofSeconds(5))
                    .setTaskStartToCloseTimeout(Duration.ofSeconds(2))
                    .setTaskList(taskList)
                    .setRetryOptions(new RetryOptions.Builder()
                            .setMinimumAttempts(2)
                            .setMaximumInterval(Duration.ofSeconds(1))
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumAttempts(3)
                            .build())
                    .build();
            child = Workflow.newWorkflowStub(ITestChild.class, options);
        }

        @Override
        public String execute() {
            return child.execute("wash dishes");
        }
    }

    public static class AngryChild implements ITestChild {

        static int invocationCount;

        @Override
        public String execute(String arg) {
            if (!Workflow.isReplaying()) {
                invocationCount++;
            }
            throw new UnsupportedOperationException("no way");
        }
    }

    @Test
    public void testChildWorkflowRetry() {
        AngryChild.invocationCount = 0;
        startWorkerFor(TestChildWorkflowRetryWorkflow.class, AngryChild.class);

        WorkflowOptions.Builder options = new WorkflowOptions.Builder();
        options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
        options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
        options.setTaskList(taskList);
        TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
        try {
            client.execute();
        } catch (WorkflowException e) {
            assertTrue(e.getCause() instanceof ChildWorkflowFailureException);
            assertTrue(e.getCause().getCause() instanceof UnsupportedOperationException);
            assertEquals("no way", e.getCause().getCause().getMessage());
        }
        assertEquals(3, AngryChild.invocationCount);
    }

    public static class TestSignalExternalWorkflow implements TestWorkflowSignaled {

        private final SignalingChild child = Workflow.newWorkflowStub(SignalingChild.class);

        private final CompletablePromise<Object> fromSignal = Workflow.newPromise();

        @Override
        public String execute() {
            Promise<String> result = Async.invoke(child::execute, "Hello", Workflow.getWorkflowInfo().getWorkflowId());
            return result.get() + " " + fromSignal.get() + "!";
        }

        @Override
        public void signal1(String arg) {
            fromSignal.complete(arg);
        }
    }

    public interface SignalingChild {
        @WorkflowMethod
        String execute(String arg, String parentWorkflowID);
    }

    public static class SignalingChildImpl implements SignalingChild {

        @Override
        public String execute(String greeting, String parentWorkflowID) {
            WorkflowExecution parentExecution = new WorkflowExecution().setWorkflowId(parentWorkflowID);
            TestWorkflowSignaled parent = Workflow.newWorkflowStub(TestWorkflowSignaled.class, parentExecution);
            parent.signal1("World");
            return greeting;
        }
    }

    @Test
    public void testSignalExternalWorkflow() {
        startWorkerFor(TestSignalExternalWorkflow.class, SignalingChildImpl.class);
        WorkflowOptions.Builder options = new WorkflowOptions.Builder();
        options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
        options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
        options.setTaskList(taskList);
        TestWorkflowSignaled client = workflowClient.newWorkflowStub(TestWorkflowSignaled.class, options.build());
        assertEquals("Hello World!", client.execute());
    }

    public static class TestSignalExternalWorkflowFailure implements TestWorkflow1 {

        @Override
        public String execute() {
            WorkflowExecution parentExecution = new WorkflowExecution().setWorkflowId("invalid id");
            TestWorkflowSignaled workflow = Workflow.newWorkflowStub(TestWorkflowSignaled.class, parentExecution);
            workflow.signal1("World");
            return "ignored";
        }
    }

    @Test
    public void testSignalExternalWorkflowFailure() {
        startWorkerFor(TestSignalExternalWorkflowFailure.class);
        WorkflowOptions.Builder options = new WorkflowOptions.Builder();
        options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
        options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
        options.setTaskList(taskList);
        TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
        try {
            client.execute();
            fail("unreachable");
        } catch (WorkflowFailureException e) {
            assertTrue(e.getCause() instanceof SignalExternalWorkflowException);
            assertEquals("invalid id",
                    ((SignalExternalWorkflowException) e.getCause()).getSignaledExecution().getWorkflowId());
            assertEquals(SignalExternalWorkflowExecutionFailedCause.UNKNOWN_EXTERNAL_WORKFLOW_EXECUTION,
                    ((SignalExternalWorkflowException) e.getCause()).getFailureCause());
        }
    }

    public static class TestSignalExternalWorkflowImmediateCancellation implements TestWorkflow1 {

        @Override
        public String execute() {
            WorkflowExecution parentExecution = new WorkflowExecution().setWorkflowId("invalid id");
            TestWorkflowSignaled workflow = Workflow.newWorkflowStub(TestWorkflowSignaled.class, parentExecution);
            CompletablePromise<Void> signal = Workflow.newPromise();
            CancellationScope scope = Workflow.newCancellationScope(() -> {
                signal.completeFrom(Async.invoke(workflow::signal1, "World"));
            });
            scope.cancel();
            try {
                signal.get();
            } catch (IllegalArgumentException e) {
                // expected
            }
            return "result";
        }
    }

    @Test
    public void testSignalExternalWorkflowImmediateCancellation() {
        startWorkerFor(TestSignalExternalWorkflowImmediateCancellation.class);
        WorkflowOptions.Builder options = new WorkflowOptions.Builder();
        options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
        options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
        options.setTaskList(taskList);
        TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
        assertEquals("result", client.execute());
    }

    public static class TestChildWorkflowAsyncRetryWorkflow implements TestWorkflow1 {

        private final ITestChild child;

        public TestChildWorkflowAsyncRetryWorkflow() {
            ChildWorkflowOptions options = new ChildWorkflowOptions.Builder()
                    .setExecutionStartToCloseTimeout(Duration.ofSeconds(5))
                    .setTaskStartToCloseTimeout(Duration.ofSeconds(2))
                    .setTaskList(taskList)
                    .setRetryOptions(new RetryOptions.Builder()
                            .setMinimumAttempts(2)
                            .setMaximumInterval(Duration.ofSeconds(1))
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumAttempts(3)
                            .build())
                    .build();
            child = Workflow.newWorkflowStub(ITestChild.class, options);
        }

        @Override
        public String execute() {
            return Async.invoke(child::execute, "wash dishes").get();
        }
    }

    @Test
    public void testChildWorkflowAsyncRetry() {
        AngryChild.invocationCount = 0;
        startWorkerFor(TestChildWorkflowAsyncRetryWorkflow.class, AngryChild.class);

        WorkflowOptions.Builder options = new WorkflowOptions.Builder();
        options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
        options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
        options.setTaskList(taskList);
        TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
        try {
            client.execute();
        } catch (WorkflowException e) {
            assertTrue(e.getCause() instanceof ChildWorkflowFailureException);
            assertTrue(e.getCause().getCause() instanceof UnsupportedOperationException);
            assertEquals("no way", e.getCause().getCause().getMessage());
        }
        assertEquals(3, AngryChild.invocationCount);
    }


    /**
     * Used to test that worker rejects activities with DoNotCompleteOnReturn annotation on interface.
     */
    public interface ActivitiesWithDoNotCompleteAnnotation {
        @DoNotCompleteOnReturn
        void activity();
    }

    public class ActivitiesWithDoNotCompleteAnnotationImpl implements ActivitiesWithDoNotCompleteAnnotation {
        @Override
        public void activity() {
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testActivitiesWithDoNotCompleteAnnotationInterface() {
        worker.registerActivitiesImplementations(new ActivitiesWithDoNotCompleteAnnotationImpl());
    }

    public interface TestActivities {

        String activityWithDelay(long milliseconds, boolean heartbeatMoreThanOnce);

        String activity();

        @ActivityMethod(name = "customActivity1")
        String activity1(String input);

        String activity2(String a1, int a2);

        String activity3(String a1, int a2, int a3);

        String activity4(String a1, int a2, int a3, int a4);

        String activity5(String a1, int a2, int a3, int a4, int a5);

        String activity6(String a1, int a2, int a3, int a4, int a5, int a6);

        void proc();

        void proc1(String input);

        void proc2(String a1, int a2);

        void proc3(String a1, int a2, int a3);

        void proc4(String a1, int a2, int a3, int a4);

        void proc5(String a1, int a2, int a3, int a4, int a5);

        void proc6(String a1, int a2, int a3, int a4, int a5, int a6);

        void throwIO();

        @ActivityMethod(scheduleToStartTimeoutSeconds = 5, scheduleToCloseTimeoutSeconds = 5,
                heartbeatTimeoutSeconds = 5, startToCloseTimeoutSeconds = 10)
        @MethodRetry(initialIntervalSeconds = 1, maximumIntervalSeconds = 1, minimumAttempts = 2, maximumAttempts = 3)
        void throwIOAnnotated();
    }

    private static class TestActivitiesImpl implements TestActivities {

        final ActivityCompletionClient completionClient;
        final List<String> invocations = Collections.synchronizedList(new ArrayList<>());
        final List<String> procResult = Collections.synchronizedList(new ArrayList<>());
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 100, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());

        private TestActivitiesImpl(ActivityCompletionClient completionClient) {
            this.completionClient = completionClient;
        }

        void close() {
            executor.shutdown();
        }

        void assertInvocations(String... expected) {
            assertEquals(Arrays.asList(expected), invocations);
        }

        @Override
        @DoNotCompleteOnReturn
        public String activityWithDelay(long delay, boolean heartbeatMoreThanOnce) {
            byte[] taskToken = Activity.getTaskToken();
            executor.execute(() -> {
                invocations.add("activityWithDelay");
                long start = System.currentTimeMillis();
                try {
                    int count = 0;
                    while (System.currentTimeMillis() - start < delay) {
                        if (heartbeatMoreThanOnce || count == 0) {
                            completionClient.heartbeat(taskToken, "heartbeatValue");
                        }
                        count++;
                        Thread.sleep(100);
                    }
                    completionClient.complete(taskToken, "activity");
                } catch (InterruptedException e) {
                    throw new RuntimeException("unexpected", e);
                } catch (ActivityNotExistsException | ActivityCancelledException e) {
                    completionClient.reportCancellation(taskToken, null);
                }
            });
            return "ignored";
        }

        @Override
        public String activity() {
            invocations.add("activity");
            return "activity";
        }

        @Override
        public String activity1(String a1) {
            invocations.add("activity1");
            return a1;
        }

        @Override
        public String activity2(String a1, int a2) {
            invocations.add("activity2");
            return a1 + a2;
        }

        @Override
        public String activity3(String a1, int a2, int a3) {
            invocations.add("activity3");
            return a1 + a2 + a3;
        }

        @DoNotCompleteOnReturn
        @Override
        public String activity4(String a1, int a2, int a3, int a4) {
            byte[] taskToken = Activity.getTaskToken();
            executor.execute(() -> {
                invocations.add("activity4");
                completionClient.complete(taskToken, a1 + a2 + a3 + a4);
            });
            return "ignored";
        }

        @DoNotCompleteOnReturn
        @Override
        public String activity5(String a1, int a2, int a3, int a4, int a5) {
            WorkflowExecution execution = Activity.getWorkflowExecution();
            String id = Activity.getTask().getActivityId();
            executor.execute(() -> {
                invocations.add("activity5");
                completionClient.complete(execution, id, a1 + a2 + a3 + a4 + a5);
            });
            return "ignored";
        }

        @Override
        public String activity6(String a1, int a2, int a3, int a4, int a5, int a6) {
            invocations.add("activity6");
            return a1 + a2 + a3 + a4 + a5 + a6;
        }

        public void proc() {
            invocations.add("proc");
            procResult.add("proc");
        }

        public void proc1(String a1) {
            invocations.add("proc1");
            procResult.add(a1);
        }

        public void proc2(String a1, int a2) {
            invocations.add("proc2");
            procResult.add(a1 + a2);
        }

        public void proc3(String a1, int a2, int a3) {
            invocations.add("proc3");
            procResult.add(a1 + a2 + a3);
        }

        public void proc4(String a1, int a2, int a3, int a4) {
            invocations.add("proc4");
            procResult.add(a1 + a2 + a3 + a4);
        }

        public void proc5(String a1, int a2, int a3, int a4, int a5) {
            invocations.add("proc5");
            procResult.add(a1 + a2 + a3 + a4 + a5);
        }

        public void proc6(String a1, int a2, int a3, int a4, int a5, int a6) {
            invocations.add("proc6");
            procResult.add(a1 + a2 + a3 + a4 + a5 + a6);
        }

        @Override
        public void throwIO() {
            invocations.add("throwIO");
            try {
                throw new IOException("simulated IO problem");
            } catch (IOException e) {
                throw Activity.throwWrapped(e);
            }
        }

        @Override
        public void throwIOAnnotated() {
            invocations.add("throwIOAnnotated");
            try {
                throw new IOException("simulated IO problem");
            } catch (IOException e) {
                throw Activity.throwWrapped(e);
            }
        }
    }

    public interface TestMultiargsWorkflows {
        @WorkflowMethod
        String func();

        @WorkflowMethod(name = "func1", taskList = "WorkflowTest-testAsyncStart",
                workflowIdReusePolicy = WorkflowIdReusePolicy.RejectDuplicate,
                executionStartToCloseTimeoutSeconds = 10)
        String func1(String input);

        @WorkflowMethod
        String func2(String a1, int a2);

        @WorkflowMethod
        String func3(String a1, int a2, int a3);

        @WorkflowMethod
        String func4(String a1, int a2, int a3, int a4);

        @WorkflowMethod
        String func5(String a1, int a2, int a3, int a4, int a5);

        @WorkflowMethod
        String func6(String a1, int a2, int a3, int a4, int a5, int a6);

        @WorkflowMethod
        void proc();

        @WorkflowMethod
        void proc1(String input);

        @WorkflowMethod
        void proc2(String a1, int a2);

        @WorkflowMethod
        void proc3(String a1, int a2, int a3);

        @WorkflowMethod
        void proc4(String a1, int a2, int a3, int a4);

        @WorkflowMethod
        void proc5(String a1, int a2, int a3, int a4, int a5);

        @WorkflowMethod
        void proc6(String a1, int a2, int a3, int a4, int a5, int a6);
    }

    public static class TestMultiargsWorkflowsImpl implements TestMultiargsWorkflows {
        static List<String> procResult = Collections.synchronizedList(new ArrayList<>());

        public String func() {
            return "func";
        }

        public String func1(String a1) {
            return a1;
        }

        public String func2(String a1, int a2) {
            return a1 + a2;
        }

        public String func3(String a1, int a2, int a3) {
            return a1 + a2 + a3;
        }

        public String func4(String a1, int a2, int a3, int a4) {
            return a1 + a2 + a3 + a4;
        }

        public String func5(String a1, int a2, int a3, int a4, int a5) {
            return a1 + a2 + a3 + a4 + a5;
        }

        public String func6(String a1, int a2, int a3, int a4, int a5, int a6) {
            return a1 + a2 + a3 + a4 + a5 + a6;
        }

        public void proc() {
            procResult.add("proc");
        }

        public void proc1(String a1) {
            procResult.add(a1);
        }

        public void proc2(String a1, int a2) {
            procResult.add(a1 + a2);
        }

        public void proc3(String a1, int a2, int a3) {
            procResult.add(a1 + a2 + a3);
        }

        public void proc4(String a1, int a2, int a3, int a4) {
            procResult.add(a1 + a2 + a3 + a4);
        }

        public void proc5(String a1, int a2, int a3, int a4, int a5) {
            procResult.add(a1 + a2 + a3 + a4 + a5);
        }

        public void proc6(String a1, int a2, int a3, int a4, int a5, int a6) {
            procResult.add(a1 + a2 + a3 + a4 + a5 + a6);
        }
    }

}
