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
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.DataConverter;
import com.uber.cadence.JsonDataConverter;
import com.uber.cadence.StartWorkflowOptions;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.worker.ActivityWorker;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SyncWorkfowTest {

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
        log = LogFactory.getLog(SyncWorkfowTest.class);

    }

    private static WorkflowService.Iface service;
    private static SyncWorkflowWorker workflowWorker;
    private static ActivityWorker activityWorker;
    private static TestActivitiesImpl activities;
    private static WorkflowExternal clientFactory;
    private static StartWorkflowOptions startWorkflowOptions;

    @BeforeClass
    public static void setUpService() {
        WorkflowServiceTChannel.ClientOptions.Builder optionsBuilder = new WorkflowServiceTChannel.ClientOptions.Builder();
        service = new WorkflowServiceTChannel(host, port, serviceName, optionsBuilder.build());
        activityWorker = new ActivityWorker(service, domain, taskList);
        activities = new TestActivitiesImpl();
        activityWorker.addActivityImplementation(activities);
        workflowWorker = new SyncWorkflowWorker(service, domain, taskList);
        clientFactory = new WorkflowExternal(service, domain, dataConverter);
        activityWorker.start();
        workflowWorker.start();
        startWorkflowOptions = new StartWorkflowOptions();
        startWorkflowOptions.setExecutionStartToCloseTimeoutSeconds(60);
        startWorkflowOptions.setTaskStartToCloseTimeoutSeconds(2);
        startWorkflowOptions.setTaskList(taskList);
    }

    @AfterClass
    public static void tearDownService() {
        activityWorker.shutdown();
        workflowWorker.shutdown();
    }

    @Before
    public void setUp() {
        activities.procResult.clear();
    }

    public interface TestWorkflow1 {
        @WorkflowMethod
        String execute();
    }

    public interface TestWorkflow2 {
        @WorkflowMethod(name="testActivity")
        String execute();
    }

    public interface QueryableWorkflow {
        @WorkflowMethod
        String execute();

        @QueryMethod
        String getState();
    }

    public static class TestSyncWorkflowImpl implements TestWorkflow1 {

        @Override
        public String execute() {
            AtomicReference<String> a1 = new AtomicReference<>();
            TestActivities activities = Workflow.newActivityClient(TestActivities.class);
            WorkflowThread t = Workflow.newThread(() -> {
                a1.set(activities.activity());
            });
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
        workflowWorker.addWorkflow(TestSyncWorkflowImpl.class);
        TestWorkflow1 client = clientFactory.newClient(TestWorkflow1.class, startWorkflowOptions);
        String result = client.execute();
        assertEquals("activity10", result);
    }

    public static class TestAsyncActivityWorkflowImpl implements TestWorkflow1 {

        @Override
        public String execute() {
            TestActivities testActivities = Workflow.newActivityClient(TestActivities.class);
            try {
                assertEquals("activity", Workflow.executeAsync(testActivities::activity).get());
                assertEquals("1", Workflow.executeAsync(testActivities::activity1, "1").get());
                assertEquals("12", Workflow.executeAsync(testActivities::activity2, "1", 2).get());
                assertEquals("123", Workflow.executeAsync(testActivities::activity3, "1", 2, 3).get());
                assertEquals("1234", Workflow.executeAsync(testActivities::activity4, "1", 2, 3, 4).get());
                assertEquals("12345", Workflow.executeAsync(testActivities::activity5, "1", 2, 3, 4, 5).get());
                assertEquals("123456", Workflow.executeAsync(testActivities::activity6, "1", 2, 3, 4, 5, 6).get());

                Workflow.executeAsync(testActivities::proc).get();
                Workflow.executeAsync(testActivities::proc1, "1").get();
                Workflow.executeAsync(testActivities::proc2, "1", 2).get();
                Workflow.executeAsync(testActivities::proc3, "1", 2, 3).get();
                Workflow.executeAsync(testActivities::proc4, "1", 2, 3, 4).get();
                Workflow.executeAsync(testActivities::proc5, "1", 2, 3, 4, 5).get();
                Workflow.executeAsync(testActivities::proc6, "1", 2, 3, 4, 5, 6).get();
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
        workflowWorker.addWorkflow(TestAsyncActivityWorkflowImpl.class);
        TestWorkflow1 client = clientFactory.newClient(TestWorkflow1.class, startWorkflowOptions);
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
        workflowWorker.addWorkflow(TestTimerWorkflowImpl.class);
        TestWorkflow2 client = clientFactory.newClient(TestWorkflow2.class, startWorkflowOptions);
        String result = client.execute();
        assertEquals("testTimer", result);
    }

    public static class TestSignalWorkflowImpl implements QueryableWorkflow {

        String state = "initial";

        @Override
        public String execute() {
            try {
                QueueConsumer<String> signalQueue = Workflow.getSignalQueue("testSignal", String.class);
                String signal1 = signalQueue.take();
                state = signal1;
                String signal2 = signalQueue.take();
                state = signal2;
                return (signal1 + signal2);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getState() {
            return state;
        }
    }

    @Test
    public void testSignal() throws TimeoutException, InterruptedException {
        workflowWorker.addWorkflow(TestSignalWorkflowImpl.class);
        QueryableWorkflow client = clientFactory.newClient(QueryableWorkflow.class, startWorkflowOptions);
        WorkflowExternalResult<String> result = WorkflowExternal.executeWorkflow(client::execute);
        assertEquals("initial", client.getState());
        result.signal("testSignal", "Hello ");
        assertEquals("Hello ", client.getState());
        result.signal("testSignal", "World!");
        assertEquals("World!", client.getState());
        assertEquals("Hello World!", result.getResult());
    }


    static final AtomicInteger decisionCount = new AtomicInteger();
    static final CompletableFuture<Boolean> sendSignal = new CompletableFuture<>();

    public static class TestSignalDuringLastDecisionWorkflowImpl implements TestWorkflow1 {


        @Override
        public String execute() {
            try {
                QueueConsumer<String> signalQueue = Workflow.getSignalQueue("testSignal", String.class);
                String signal = signalQueue.poll(0, TimeUnit.MILLISECONDS);
                if (decisionCount.incrementAndGet() == 1) {
                    sendSignal.complete(true);
                    // Never sleep in real workflow using Thread.sleep.
                    // Here it is to simulate race condition.
                    Thread.sleep(500);
                }
                if (signal == null) {
                    return null;
                } else {
                    return signal;
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void testSignalDuringLastDecision() throws TimeoutException, InterruptedException {
        workflowWorker.addWorkflow(TestSignalDuringLastDecisionWorkflowImpl.class);
        TestWorkflow1 client = clientFactory.newClient(TestWorkflow1.class, startWorkflowOptions);
        WorkflowExternalResult<String> result = WorkflowExternal.executeWorkflow(client::execute);
        try {
            sendSignal.get(2, TimeUnit.SECONDS);
            result.signal("testSignal", "Signal Input");
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
                WorkflowFuture<Void> f = new WorkflowFuture<>();
                timer1.thenApply((e) -> {
                    timer2.get(); // This is prohibited
                    f.complete(null);
                    return null;
                });
                f.get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            return "testTimerBlocked";
        }
    }

    @Test
    public void testTimerCallbackBlocked() {
        workflowWorker.addWorkflow(TestTimerCallbackBlockedWorkflowImpl.class);
        StartWorkflowOptions options = new StartWorkflowOptions();
        options.setExecutionStartToCloseTimeoutSeconds(2);
        options.setTaskStartToCloseTimeoutSeconds(1);
        options.setTaskList(taskList);
        TestWorkflow1 client = clientFactory.newClient(TestWorkflow1.class, options);
        try {
            client.execute();
            fail("failure expected");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Callback thread blocked"));
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
