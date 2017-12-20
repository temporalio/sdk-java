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
package com.uber.cadence;

// TODO (Cadence): Fix replayer
public class WorkflowReplayer<T> {

//    private final class WorkflowReplayerPOJOFactoryFactory extends POJOWorkflowDefinitionFactoryFactory {
//
//        private final T workflowImplementation;
//
//        private WorkflowReplayerPOJOFactoryFactory(T workflowImplementation) throws InstantiationException, IllegalAccessException {
//            this.workflowImplementation = workflowImplementation;
//            super.addWorkflowImplementationType(workflowImplementation.getClass());
//        }
//
//        @Override
//        protected POJOWorkflowImplementationFactory getImplementationFactory(Class<?> workflowImplementationType,
//                Class<?> workflowInteface, WorkflowType workflowType) {
//            return new POJOWorkflowImplementationFactory() {
//
//                @Override
//                public Object newInstance(DecisionContext decisionContext) throws Exception {
//                    return workflowImplementation;
//                }
//
//                @Override
//                public Object newInstance(DecisionContext decisionContext, Object[] constructorArgs) throws Exception {
//                    return workflowImplementation;
//                }
//
//                @Override
//                public void deleteInstance(Object instance) {
//                }
//            };
//        }
//    }
//
//    private final DecisionTaskWithHistoryIterator historyIterator;
//
//    private final AsyncDecisionTaskHandler taskHandler;
//
//    private int replayUpToEventId;
//
//    public WorkflowReplayer(WorkflowService.Iface service, String domain, WorkflowExecution workflowExecution,
//            Class<T> workflowImplementationType) throws InstantiationException, IllegalAccessException {
//        POJOWorkflowDefinitionFactoryFactory ff = new POJOWorkflowDefinitionFactoryFactory();
//        ff.addWorkflowImplementationType(workflowImplementationType);
//        historyIterator = new ServiceDecisionTaskIterator(service, domain, workflowExecution);
//        taskHandler = new AsyncDecisionTaskHandler(ff);
//    }
//
//    public WorkflowReplayer(WorkflowService.Iface service, String domain, WorkflowExecution workflowExecution,
//            final T workflowImplementation) throws InstantiationException, IllegalAccessException {
//        WorkflowDefinitionFactoryFactory ff = new WorkflowReplayerPOJOFactoryFactory(workflowImplementation);
//        historyIterator = new ServiceDecisionTaskIterator(service, domain, workflowExecution);
//        taskHandler = new AsyncDecisionTaskHandler(ff);
//    }
//
//    public WorkflowReplayer(WorkflowService.Iface service, String domain, WorkflowExecution workflowExecution,
//            WorkflowDefinitionFactoryFactory workflowDefinitionFactoryFactory)
//            throws InstantiationException, IllegalAccessException {
//        historyIterator = new ServiceDecisionTaskIterator(service, domain, workflowExecution);
//        taskHandler = new AsyncDecisionTaskHandler(workflowDefinitionFactoryFactory);
//    }
//
//    public WorkflowReplayer(Iterable<HistoryEvent> history, WorkflowExecution workflowExecution,
//            Class<T> workflowImplementationType) throws InstantiationException, IllegalAccessException {
//        POJOWorkflowDefinitionFactoryFactory ff = new POJOWorkflowDefinitionFactoryFactory();
//        ff.addWorkflowImplementationType(workflowImplementationType);
//        historyIterator = new HistoryIterableDecisionTaskIterator(workflowExecution, history);
//        taskHandler = new AsyncDecisionTaskHandler(ff);
//    }
//
//    public WorkflowReplayer(Iterable<HistoryEvent> history, WorkflowExecution workflowExecution, final T workflowImplementation)
//            throws InstantiationException, IllegalAccessException {
//        WorkflowDefinitionFactoryFactory ff = new WorkflowReplayerPOJOFactoryFactory(workflowImplementation);
//        historyIterator = new HistoryIterableDecisionTaskIterator(workflowExecution, history);
//        taskHandler = new AsyncDecisionTaskHandler(ff);
//    }
//
//    public WorkflowReplayer(Iterable<HistoryEvent> history, WorkflowExecution workflowExecution,
//            WorkflowDefinitionFactoryFactory workflowDefinitionFactoryFactory)
//            throws InstantiationException, IllegalAccessException {
//        historyIterator = new HistoryIterableDecisionTaskIterator(workflowExecution, history);
//        taskHandler = new AsyncDecisionTaskHandler(workflowDefinitionFactoryFactory);
//    }
//
//    public WorkflowReplayer(Iterator<PollForDecisionTaskResponse> decisionTasks, Class<T> workflowImplementationType)
//            throws InstantiationException, IllegalAccessException {
//        POJOWorkflowDefinitionFactoryFactory ff = new POJOWorkflowDefinitionFactoryFactory();
//        ff.addWorkflowImplementationType(workflowImplementationType);
//        historyIterator = decisionTasks;
//        taskHandler = new AsyncDecisionTaskHandler(ff);
//    }
//
//    public WorkflowReplayer(Iterator<PollForDecisionTaskResponse> decisionTasks, final T workflowImplementation)
//            throws InstantiationException, IllegalAccessException {
//        WorkflowDefinitionFactoryFactory ff = new WorkflowReplayerPOJOFactoryFactory(workflowImplementation);
//        historyIterator = decisionTasks;
//        taskHandler = new AsyncDecisionTaskHandler(ff);
//    }
//
//    public WorkflowReplayer(Iterator<PollForDecisionTaskResponse> decisionTasks,
//            WorkflowDefinitionFactoryFactory workflowDefinitionFactoryFactory)
//            throws InstantiationException, IllegalAccessException {
//        historyIterator = decisionTasks;
//        taskHandler = new AsyncDecisionTaskHandler(workflowDefinitionFactoryFactory);
//    }
//
//    public int getReplayUpToEventId() {
//        return replayUpToEventId;
//    }
//
//    /**
//     * The replay stops at the event with the given eventId. Default is 0.
//     *
//     * @param replayUpToEventId
//     *            0 means the whole history.
//     */
//    public void setReplayUpToEventId(int replayUpToEventId) {
//        this.replayUpToEventId = replayUpToEventId;
//    }
//
//    public RespondDecisionTaskCompletedRequest replay() throws Exception {
//        return taskHandler.handleDecisionTask(historyIterator);
//    }
//
//    @SuppressWarnings("unchecked")
//    public T loadWorkflow() throws Exception {
//        WorkflowDefinition definition = taskHandler.loadWorkflowThroughReplay(historyIterator);
//        POJOWorkflowDefinition pojoDefinition = (POJOWorkflowDefinition) definition;
//        return (T) pojoDefinition.getImplementationInstance();
//    }
//
//    public List<AsyncTaskInfo> getAsynchronousThreadDump() throws Exception {
//        return taskHandler.getAsynchronousThreadDump(historyIterator);
//    }
//
//    public String getAsynchronousThreadDumpAsString() throws Exception {
//        return taskHandler.getAsynchronousThreadDumpAsString(historyIterator);
//    }
}
