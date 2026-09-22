package io.temporal.aws.lambda;

import io.temporal.common.converter.EncodedValues;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;

interface WorkerRegistrar {
  void registerWorkflowImplementationTypes(Class<?>... workflowImplementationClasses);

  void registerWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>... workflowImplementationClasses);

  <R> void registerWorkflowImplementationFactory(
      Class<R> workflowInterface, Functions.Func<R> factory);

  <R> void registerWorkflowImplementationFactory(
      Class<R> workflowInterface,
      Functions.Func1<EncodedValues, R> factory,
      WorkflowImplementationOptions options);

  <R> void registerWorkflowImplementationFactory(
      Class<R> workflowInterface, Functions.Func<R> factory, WorkflowImplementationOptions options);

  void registerActivitiesImplementations(Object... activityImplementations);

  void registerNexusServiceImplementation(Object... nexusServiceImplementations);
}
