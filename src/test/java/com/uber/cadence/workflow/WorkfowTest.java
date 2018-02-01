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

import com.uber.cadence.client.CadenceClient;
import com.uber.cadence.client.WorkflowExternalResult;
import com.uber.cadence.internal.DataConverter;
import com.uber.cadence.internal.JsonDataConverter;
import com.uber.cadence.internal.StartWorkflowOptions;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.worker.Worker;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class WorkfowTest {

    // TODO: Make this configuratble instead of always using local instance.
    private static final String host = "127.0.0.1";
    private static final int port = 7933;
    private static final String serviceName = "cadence-frontend";
    private static final String domain = "UnitTest";
    private static final String taskList = "UnitTest";
    private static final DataConverter dataConverter = new JsonDataConverter();
    private static final Log log;

    static {
        LogManager.resetConfiguration();

        final PatternLayout layout = new PatternLayout();
        layout.setConversionPattern("%-4r %-30c{1} %x: %m%n");

        final ConsoleAppender dst = new ConsoleAppender(layout, ConsoleAppender.SYSTEM_OUT);
        dst.setThreshold(Level.DEBUG);

        final Logger root = Logger.getRootLogger();
        root.removeAllAppenders();
        root.addAppender(dst);
        root.setLevel(Level.DEBUG);

        Logger.getLogger("io.netty").setLevel(Level.INFO);
        log = LogFactory.getLog(WorkfowTest.class);

    }

    private static WorkflowService.Iface service;
    private static Worker worker;
    private static TestActivitiesImpl activities;
    private static CadenceClient cadenceClient;
    private static ActivitySchedulingOptions activitySchedulingOptions;

    @BeforeClass
    public static void setUpService() {
        WorkflowServiceTChannel.ClientOptions.Builder optionsBuilder = new WorkflowServiceTChannel.ClientOptions.Builder();
        service = new WorkflowServiceTChannel(host, port, serviceName, optionsBuilder.build());
        worker = new Worker(service, domain, taskList, null);
        activities = new TestActivitiesImpl();
        worker.addActivities(activities);
        cadenceClient = new CadenceClient(service, domain, dataConverter);
        worker.start();
        newStartWorkflowOptions();
        activitySchedulingOptions = new ActivitySchedulingOptions();
        activitySchedulingOptions.setTaskList(taskList);
        activitySchedulingOptions.setHeartbeatTimeoutSeconds(10);
        activitySchedulingOptions.setScheduleToCloseTimeoutSeconds(20);
        activitySchedulingOptions.setScheduleToStartTimeoutSeconds(10);
        activitySchedulingOptions.setStartToCloseTimeoutSeconds(10);
    }

    private static StartWorkflowOptions newStartWorkflowOptions() {
        StartWorkflowOptions result = new StartWorkflowOptions();
        result.setExecutionStartToCloseTimeoutSeconds(60);
        result.setTaskStartToCloseTimeoutSeconds(2);
        result.setTaskList(taskList);
        return result;
    }

    @AfterClass
    public static void tearDownService() {
        worker.shutdown(100, TimeUnit.MILLISECONDS);
    }

    @Before
    public void setUp() {
        activities.procResult.clear();
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

    public interface QueryableWorkflow {
        @WorkflowMethod
        String execute();

        @QueryMethod
        String getState();

        @SignalMethod(name = "testSignal")
        void signal(String value);
    }

    public static class TestSyncWorkflowImpl implements TestWorkflow1 {

        @Override
        public String execute() {
            AtomicReference<String> a1 = new AtomicReference<>();
            TestActivities activities = Workflow.newActivityClient(TestActivities.class, activitySchedulingOptions);
            WorkflowThread t = Workflow.newThread(() -> a1.set(activities.activity()));
            t.start();
            try {
                t.join(3000);
                WorkflowThread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return activities.activity2(a1.get(), 10);
        }
    }

    @Test
    public void testSync() {
        worker.addWorkflowType(TestSyncWorkflowImpl.class);
        TestWorkflow1 client = cadenceClient.newWorkflowClient(TestWorkflow1.class, newStartWorkflowOptions());
        String result = client.execute();
        assertEquals("activity10", result);
    }

    public static class TestAsyncActivityWorkflowImpl implements TestWorkflow1 {

        @Override
        public String execute() {
            TestActivities testActivities = Workflow.newActivityClient(TestActivities.class, activitySchedulingOptions);
            try {
                assertEquals("activity", Workflow.async(testActivities::activity).get());
                assertEquals("1", Workflow.async(testActivities::activity1, "1").get());
                assertEquals("12", Workflow.async(testActivities::activity2, "1", 2).get());
                assertEquals("123", Workflow.async(testActivities::activity3, "1", 2, 3).get());
                assertEquals("1234", Workflow.async(testActivities::activity4, "1", 2, 3, 4).get());
                assertEquals("12345", Workflow.async(testActivities::activity5, "1", 2, 3, 4, 5).get());
                assertEquals("123456", Workflow.async(testActivities::activity6, "1", 2, 3, 4, 5, 6).get());

                Workflow.async(testActivities::proc).get();
                Workflow.async(testActivities::proc1, "1").get();
                Workflow.async(testActivities::proc2, "1", 2).get();
                Workflow.async(testActivities::proc3, "1", 2, 3).get();
                Workflow.async(testActivities::proc4, "1", 2, 3, 4).get();
                Workflow.async(testActivities::proc5, "1", 2, 3, 4, 5).get();
                Workflow.async(testActivities::proc6, "1", 2, 3, 4, 5, 6).get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            return "workflow";
        }
    }

    @Test
    public void testAsyncActivity() {
        worker.addWorkflowType(TestAsyncActivityWorkflowImpl.class);
        TestWorkflow1 client = cadenceClient.newWorkflowClient(TestWorkflow1.class, newStartWorkflowOptions());
        String result = client.execute();
        assertEquals("workflow", result);

        assertEquals("proc", activities.procResult.get(0));
        assertEquals("1", activities.procResult.get(1));
        assertEquals("12", activities.procResult.get(2));
        assertEquals("123", activities.procResult.get(3));
        assertEquals("1234", activities.procResult.get(4));
        assertEquals("12345", activities.procResult.get(5));
        assertEquals("123456", activities.procResult.get(6));
    }

    public static class TestTimerWorkflowImpl implements TestWorkflow2 {

        @Override
        public String execute() {
            WorkflowFuture<Void> timer1 = Workflow.newTimer(1);
            WorkflowFuture<Void> timer2 = Workflow.newTimer(2);

            try {
                long time = Workflow.currentTimeMillis();
                timer1.get();
                long slept = Workflow.currentTimeMillis() - time;
                assertTrue(slept > 1000);
                timer2.get();
                slept = Workflow.currentTimeMillis() - time;
                assertTrue(slept > 2000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            return "testTimer";
        }
    }

    @Test
    public void testTimer() {
        worker.addWorkflowType(TestTimerWorkflowImpl.class);
        TestWorkflow2 client = cadenceClient.newWorkflowClient(TestWorkflow2.class, newStartWorkflowOptions());
        String result = client.execute();
        assertEquals("testTimer", result);
    }

    public static class TestSignalWorkflowImpl implements QueryableWorkflow {

        String state = "initial";
        List<String> signals = new ArrayList<>();
        WorkflowFuture future = Workflow.newFuture();

        @Override
        public String execute() {
            try {
                future.get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            return signals.get(0) + signals.get(1);
        }

        @Override
        public String getState() {
            return state;
        }

        @Override
        public void signal(String value) {
            log.info("TestSignalWorkflowImpl.signal value=" + value);
            state = value;
            signals.add(value);
            if (signals.size() == 2) {
                future.complete(null);
            }
        }
    }

    @Test
    public void testSignal() throws TimeoutException, InterruptedException {
        worker.addWorkflowType(TestSignalWorkflowImpl.class);
        QueryableWorkflow client = cadenceClient.newWorkflowClient(QueryableWorkflow.class, newStartWorkflowOptions());
        // To execute workflow client.execute() would do. But we want to start workflow and immediately return.
        WorkflowExternalResult<String> result = CadenceClient.executeWorkflow(client::execute);
        assertEquals("initial", client.getState());
        client.signal("Hello ");
        assertEquals("Hello ", client.getState());
        client.signal("World!");
        assertEquals("World!", client.getState());
        assertEquals("Hello World!", result.getResult());
    }


    static final AtomicInteger decisionCount = new AtomicInteger();
    static final CompletableFuture<Boolean> sendSignal = new CompletableFuture<>();

    public static class TestSignalDuringLastDecisionWorkflowImpl implements TestWorkflowSignaled {

        private String signal;

        @Override
        public String execute() {
            if (decisionCount.incrementAndGet() == 1) {
                sendSignal.complete(true);
                // Never sleep in real workflow using Thread.sleep.
                // Here it is to simulate race condition.
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
    public void testSignalDuringLastDecision() throws TimeoutException, InterruptedException {
        worker.addWorkflowType(TestSignalDuringLastDecisionWorkflowImpl.class);
        StartWorkflowOptions options = newStartWorkflowOptions();
        options.setWorkflowId("testSignalDuringLastDecision-" + UUID.randomUUID().toString());
        TestWorkflowSignaled client = cadenceClient.newWorkflowClient(TestWorkflowSignaled.class, options);
        WorkflowExternalResult<String> result = CadenceClient.executeWorkflow(client::execute);
        try {
            sendSignal.get(2, TimeUnit.SECONDS);
            client.signal1("Signal Input");
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        assertEquals("Signal Input", result.getResult());
    }

    public static class TestTimerCallbackBlockedWorkflowImpl implements TestWorkflow1 {


        @Override
        public String execute() {
            WorkflowFuture<Void> timer1 = Workflow.newTimer(0);
            WorkflowFuture<Void> timer2 = Workflow.newTimer(1);

            try {
                WorkflowFuture<Void> f = Workflow.newFuture();
                timer1.thenApply((e) -> {
                    timer2.get(); // This is prohibited
                    f.complete(null);
                    return null;
                }).get();
                f.get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            return "testTimerBlocked";
        }
    }

    /**
     * Test that it is not allowed to block in the timer callback thread.
     */
    @Test
    public void testTimerCallbackBlocked() {
        worker.addWorkflowType(TestTimerCallbackBlockedWorkflowImpl.class);
        StartWorkflowOptions options = new StartWorkflowOptions();
        options.setExecutionStartToCloseTimeoutSeconds(2);
        options.setTaskStartToCloseTimeoutSeconds(1);
        options.setTaskList(taskList);
        TestWorkflow1 client = cadenceClient.newWorkflowClient(TestWorkflow1.class, options);
        try {
            client.execute();
            fail("failure expected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Called from non workflow or workflow callback thread"));
        }
    }

    public interface TestActivities {
        String activity();

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
    }

    private static class TestActivitiesImpl implements TestActivities {
        public List<String> procResult = Collections.synchronizedList(new ArrayList<>());

        public String activity() {
            return "activity";
        }

        public String activity1(String a1) {
            return a1;
        }

        public String activity2(String a1, int a2) {
            return a1 + a2;
        }

        public String activity3(String a1, int a2, int a3) {
            return a1 + a2 + a3;
        }

        public String activity4(String a1, int a2, int a3, int a4) {
            return a1 + a2 + a3 + a4;
        }

        public String activity5(String a1, int a2, int a3, int a4, int a5) {
            return a1 + a2 + a3 + a4 + a5;
        }

        public String activity6(String a1, int a2, int a3, int a4, int a5, int a6) {
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
