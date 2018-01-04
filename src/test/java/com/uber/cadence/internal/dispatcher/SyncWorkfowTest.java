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

import com.uber.cadence.ActivityType;
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
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;

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

    private GenericWorkflowClientExternalImpl clientExternal;
    private static WorkflowService.Iface service;
    private SyncWorkflowWorker workflowWorker;
    private ActivityWorker activityWorker;
    private Map<WorkflowType, SyncWorkflowDefinition> definitionMap = new Hashtable<>();

    private Function<WorkflowType, SyncWorkflowDefinition> factory = workflowType -> {
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
    }

    @Before
    public void setUp() {
        activityWorker = new ActivityWorker(service, domain, taskList);
        activityWorker.addActivityImplementation(new TestActivitiesImpl());
        workflowWorker = new SyncWorkflowWorker(service, domain, taskList);
        workflowWorker.setFactory(factory);
        clientExternal = new GenericWorkflowClientExternalImpl(service, domain);
        activityWorker.start();
        workflowWorker.start();
    }

    @After
    public void tearDown() {
        activityWorker.shutdown();
        workflowWorker.shutdown();
        definitionMap.clear();
    }

    @Test
    public void test() throws InterruptedException, WorkflowExecutionAlreadyStartedException, TimeoutException {
        WorkflowType type = new WorkflowType().setName("test1");
        definitionMap.put(type, (input) -> {
            AtomicReference<String> a1 = new AtomicReference<>();
            TestActivities activities = Workflow.newActivityClient(TestActivities.class);
            WorkflowThread t = Workflow.newThread(() -> {
                a1.set(activities.activity1());
            });
            t.start();
            try {
                t.join(3000);
                long time = Workflow.currentTimeMillis();
                WorkflowThread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return activities.activity2(a1.get()).getBytes();
        });
        ActivityType activity1Type = new ActivityType().setName("activity1");

        StartWorkflowExecutionParameters startParameters = new StartWorkflowExecutionParameters();
        startParameters.setExecutionStartToCloseTimeoutSeconds(60);
        startParameters.setTaskStartToCloseTimeoutSeconds(2);
        startParameters.setTaskList(taskList);
        startParameters.setInput("input".getBytes());
        startParameters.setWorkflowId("workflow1");
        startParameters.setWorkflowType(type);
        WorkflowExecution started = clientExternal.startWorkflow(startParameters);
        WorkflowExecutionCompletedEventAttributes result = WorkflowExecutionUtils.waitForWorkflowExecutionResult(service, domain, started, 10);
        assertEquals("activity1 - activity2", new String(result.getResult()));
    }

    public interface TestActivities {
        String activity1();

        String activity2(String input);

    }

    class TestActivitiesImpl implements TestActivities {
        public String activity1() {
            return "activity1";
        }

        public String activity2(String input) {
            return input + " - activity2";
        }
    }
}
