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

import com.uber.cadence.ActivityExecutionContext;
import com.uber.cadence.ActivityFailureException;
import com.uber.cadence.WorkflowExecutionAlreadyStartedException;
import com.uber.cadence.common.WorkflowExecutionUtils;
import com.uber.cadence.generic.ActivityImplementation;
import com.uber.cadence.generic.ActivityImplementationFactory;
import com.uber.cadence.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.worker.ActivityTypeExecutionOptions;
import com.uber.cadence.worker.GenericActivityWorker;
import com.uber.cadence.worker.GenericWorkflowClientExternalImpl;
import com.uber.cadence.ActivityType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
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
import java.util.concurrent.CancellationException;
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
    private GenericActivityWorker activityWorker;
    private Map<WorkflowType, SyncWorkflowDefinition> definitionMap = new Hashtable<>();
    private Map<ActivityType, ActivityImplementation> activityMap = new Hashtable<>();

    private Function<WorkflowType, SyncWorkflowDefinition> factory = new Function<WorkflowType, SyncWorkflowDefinition>() {
        @Override
        public SyncWorkflowDefinition apply(WorkflowType workflowType) {
            SyncWorkflowDefinition result = definitionMap.get(workflowType);
            if (result == null) {
                throw new IllegalArgumentException("Unknown workflow type " + workflowType);
            }
            return result;
        }
    };

    @BeforeClass
    public static void setUpService() {
        WorkflowServiceTChannel.ClientOptions.Builder optionsBuilder = new WorkflowServiceTChannel.ClientOptions.Builder();
        service = new WorkflowServiceTChannel(host, port, serviceName, optionsBuilder.build());
    }

    @Before
    public void setUp() {
        activityWorker = new GenericActivityWorker(service, domain, taskList);
        activityWorker.setActivityImplementationFactory(new ActivityImplementationFactory() {
            @Override
            public ActivityImplementation getActivityImplementation(ActivityType activityType) {
                ActivityImplementation result = activityMap.get(activityType);
                if (result == null) {
                    throw new IllegalArgumentException("Unknown activity type " + activityType);
                }
                return result;
            }
        });
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
        activityMap.clear();
    }

    @Test
    public void test() throws InterruptedException, WorkflowExecutionAlreadyStartedException, TimeoutException {
        WorkflowType type = new WorkflowType().setName("test1");
        definitionMap.put(type, (input) -> {
            AtomicReference<byte[]> a1 = new AtomicReference<>();
            WorkflowThread t = Workflow.newThread(() -> {
                a1.set(Workflow.executeActivity("activity1", "activityInput".getBytes()));
            });
            t.start();
            try {
                t.join(3000);
                long time = Workflow.currentTimeMillis();
                WorkflowThread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return Workflow.executeActivity("activity2", a1.get());
        });
        ActivityType activity1Type = new ActivityType().setName("activity1");
        activityMap.put(activity1Type, new ActivityImplementation() {
            @Override
            public ActivityTypeExecutionOptions getExecutionOptions() {
                return new ActivityTypeExecutionOptions();
            }

            @Override
            public byte[] execute(ActivityExecutionContext context) throws ActivityFailureException, CancellationException {
                return "activity1".getBytes();
            }
        });
        ActivityType activity2Type = new ActivityType().setName("activity2");
        activityMap.put(activity2Type, new ActivityImplementation() {
            @Override
            public ActivityTypeExecutionOptions getExecutionOptions() {
                return new ActivityTypeExecutionOptions();
            }

            @Override
            public byte[] execute(ActivityExecutionContext context) throws ActivityFailureException, CancellationException {
                return (new String(context.getTask().getInput()) + " - activity2").getBytes();
            }
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
        assertEquals("activity1 - activity2", new String(result.getResult()));
    }
}
