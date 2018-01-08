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

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedException;
import com.uber.cadence.WorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.common.WorkflowExecutionUtils;
import com.uber.cadence.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.worker.ActivityWorker;
import com.uber.cadence.worker.GenericWorkflowClientExternalImpl;
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
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SyncWorkfowTest {

    // TODO: Make this configuratble instead of always using local instance.
    private static final String host = "127.0.0.1";
    private static final int port = 7933;
    private static final String serviceName = "cadence-frontend";
    private static final String domain = "UnitTest";
    private static final String taskList = "UnitTest";
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

    private static GenericWorkflowClientExternalImpl clientExternal;
    private static WorkflowService.Iface service;
    private static SyncWorkflowWorker workflowWorker;
    private static ActivityWorker activityWorker;
    private static TestActivitiesImpl activities;
    private static Map<WorkflowType, SyncWorkflowDefinition> definitionMap = new Hashtable<>();

    private static Function<WorkflowType, SyncWorkflowDefinition> factory = workflowType -> {
        SyncWorkflowDefinition result = definitionMap.get(workflowType);
        if (result == null) {
            throw new IllegalArgumentException("Unknown workflow type " + workflowType);
        }
        return result;
    };

    @BeforeClass
    public static void setUpService() {
        WorkflowServiceTChannel.ClientOptions.Builder optionsBuilder = new WorkflowServiceTChannel.ClientOptions.Builder();
        service = new WorkflowServiceTChannel(host, port, serviceName, optionsBuilder.build());
        activityWorker = new ActivityWorker(service, domain, taskList);
        activities = new TestActivitiesImpl();
        activityWorker.addActivityImplementation(activities);
        workflowWorker = new SyncWorkflowWorker(service, domain, taskList);
        workflowWorker.setFactory(factory);
        clientExternal = new GenericWorkflowClientExternalImpl(service, domain);
        activityWorker.start();
        workflowWorker.start();
    }

    @AfterClass
    public static void tearDownService() {
        activityWorker.shutdown();
        workflowWorker.shutdown();
        definitionMap.clear();
    }

    @Before
    public void setUp() {
        activities.procResult.clear();
    }

    @Test
    public void testSync() throws InterruptedException, WorkflowExecutionAlreadyStartedException, TimeoutException {
        WorkflowType type = new WorkflowType().setName("testSync");
        definitionMap.put(type, (input) -> {
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
            return activities.activity2(a1.get(), 10).getBytes();
        });
        StartWorkflowExecutionParameters startParameters = new StartWorkflowExecutionParameters();
        startParameters.setExecutionStartToCloseTimeoutSeconds(60);
        startParameters.setTaskStartToCloseTimeoutSeconds(2);
        startParameters.setTaskList(taskList);
        startParameters.setInput("input".getBytes());
        startParameters.setWorkflowId("workflow1");
        startParameters.setWorkflowType(type);
        WorkflowExecution started = clientExternal.startWorkflow(startParameters);
        WorkflowExecutionCompletedEventAttributes result = WorkflowExecutionUtils.waitForWorkflowExecutionResult(service, domain, started, 10);
        assertEquals("activity10", new String(result.getResult()));
    }

    @Test
    public void testAsync() throws InterruptedException, WorkflowExecutionAlreadyStartedException, TimeoutException {
        WorkflowType type = new WorkflowType().setName("testAsync");
        definitionMap.put(type, (input) -> {
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
            return "workflow".getBytes();
        });
        StartWorkflowExecutionParameters startParameters = new StartWorkflowExecutionParameters();
        startParameters.setExecutionStartToCloseTimeoutSeconds(60);
        startParameters.setTaskStartToCloseTimeoutSeconds(2);
        startParameters.setTaskList(taskList);
        startParameters.setInput("input".getBytes());
        startParameters.setWorkflowId("workflow1");
        startParameters.setWorkflowType(type);
        WorkflowExecution started = clientExternal.startWorkflow(startParameters);
        WorkflowExecutionCompletedEventAttributes result = WorkflowExecutionUtils.waitForWorkflowExecutionResult(service, domain, started, 10);
        assertEquals("workflow", new String(result.getResult()));
        assertEquals("proc", activities.procResult.get(0));
        assertEquals("1", activities.procResult.get(1));
        assertEquals("12", activities.procResult.get(2));
        assertEquals("123", activities.procResult.get(3));
        assertEquals("1234", activities.procResult.get(4));
        assertEquals("12345", activities.procResult.get(5));
        assertEquals("123456", activities.procResult.get(6));
    }

    @Test
    public void testTimer() throws InterruptedException, WorkflowExecutionAlreadyStartedException, TimeoutException {
        WorkflowType type = new WorkflowType().setName("testTimer");
        definitionMap.put(type, (input) -> {
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
            return "testTimer".getBytes();
        });
        StartWorkflowExecutionParameters startParameters = new StartWorkflowExecutionParameters();
        startParameters.setExecutionStartToCloseTimeoutSeconds(60);
        startParameters.setTaskStartToCloseTimeoutSeconds(2);
        startParameters.setTaskList(taskList);
        startParameters.setInput("input".getBytes());
        startParameters.setWorkflowId("workflow1");
        startParameters.setWorkflowType(type);
        WorkflowExecution started = clientExternal.startWorkflow(startParameters);
        WorkflowExecutionCompletedEventAttributes result = WorkflowExecutionUtils.waitForWorkflowExecutionResult(service, domain, started, 10);
        assertEquals("testTimer", new String(result.getResult()));
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
