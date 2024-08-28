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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GenericWorkflowInterfaceTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              ExecuteWorkflowImpl.class,
              ExecuteTwoTypeVariablesWorkflowImpl.class,
              ExecuteParameterizedTypeWorkflowImpl.class,
              ExecuteParameterizedTypeWithTypeVariableWorkflowImpl.class,
              ExecuteGenericArrayTypeWorkflowWorkflowImpl.class,
              ExecuteParameterizedGenericAndArrayTypeWorkflowImpl.class,
              QueryWorkflowImpl.class,
              QueryOverrideWorkflowImpl.class,
              QueryExtensionWorkflowImpl.class,
              SignalWorkflowImpl.class,
              UpdateWorkflowImpl.class,
              WorkflowBoundImpl.class,
              WorkflowWildcardBoundImpl.class)
          .build();

  @Test
  public void testExecuteOnParentInterface() throws ExecutionException, InterruptedException {
    MessageWrapper messageWrapper = MessageWrapper.createRandom();

    ExecuteWorkflow stub = testWorkflowRule.newWorkflowStubTimeoutOptions(ExecuteWorkflow.class);
    CompletableFuture<MessageWrapper> resultF =
        WorkflowClient.execute(stub::execute, messageWrapper);
    MessageWrapper result = resultF.get();
    Assert.assertEquals(result, messageWrapper);
  }

  @Test
  public void testExecuteOnParentInterfaceTwoTypeVariables()
      throws ExecutionException, InterruptedException {
    MessageWrapper messageWrapper = MessageWrapper.createRandom();

    ExecuteTwoTypeVariablesWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ExecuteTwoTypeVariablesWorkflow.class);
    CompletableFuture<UUID> resultF = WorkflowClient.execute(stub::execute, messageWrapper);
    UUID result = resultF.get();
    Assert.assertEquals(result, UUID.fromString(messageWrapper.getMessage()));
  }

  @Test
  public void testExecuteOnParentInterfaceAndParameterizedType()
      throws ExecutionException, InterruptedException {
    GenericMessageWrapper<UUID> messageWrapper = new GenericMessageWrapper<>(UUID.randomUUID());

    ExecuteParameterizedTypeWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ExecuteParameterizedTypeWorkflow.class);
    CompletableFuture<GenericMessageWrapper<UUID>> resultF =
        WorkflowClient.execute(stub::execute, messageWrapper);
    GenericMessageWrapper<UUID> result = resultF.get();
    Assert.assertEquals(result, messageWrapper);
  }

  @Test
  public void testExecuteOnGrandparentInterfaceAndParameterizedTypeWithTypeVariable()
      throws ExecutionException, InterruptedException {
    GenericMessageWrapper<UUID> messageWrapper = new GenericMessageWrapper<>(UUID.randomUUID());

    ExecuteParameterizedTypeWithTypeVariableWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            ExecuteParameterizedTypeWithTypeVariableWorkflow.class);
    CompletableFuture<GenericMessageWrapper<UUID>> resultF =
        WorkflowClient.execute(stub::execute, messageWrapper);
    GenericMessageWrapper<UUID> result = resultF.get();
    Assert.assertEquals(result, messageWrapper);
  }

  @Test
  public void testExecuteOnParentInterfaceAndGenericArrayType()
      throws ExecutionException, InterruptedException {
    String[] args = new String[] {"arg1", "arg2"};

    ExecuteGenericArrayTypeWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ExecuteGenericArrayTypeWorkflow.class);
    CompletableFuture<String[]> resultF = WorkflowClient.execute(stub::execute, args);
    String[] result = resultF.get();
    Assert.assertArrayEquals(result, args);
  }

  @Test
  public void testExecuteOnParentInterfaceAndParameterizedGenericArrayType()
      throws ExecutionException, InterruptedException {
    GenericMessageWrapper<UUID[]> messageWrapper =
        new GenericMessageWrapper<>(new UUID[] {UUID.randomUUID(), UUID.randomUUID()});

    ExecuteParameterizedGenericAndArrayTypeWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            ExecuteParameterizedGenericAndArrayTypeWorkflow.class);
    CompletableFuture<GenericMessageWrapper<UUID[]>> resultF =
        WorkflowClient.execute(stub::execute, messageWrapper);
    GenericMessageWrapper<UUID[]> result = resultF.get();
    Assert.assertArrayEquals(result.getMessage(), messageWrapper.getMessage());
  }

  @Test
  public void testQueryOnParentInterface() {
    MessageWrapper messageWrapper = MessageWrapper.createRandom();

    QueryWorkflow stub = testWorkflowRule.newWorkflowStubTimeoutOptions(QueryWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute, messageWrapper.getMessage());

    QueryWorkflow queryStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(QueryWorkflow.class, execution.getWorkflowId());

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    Assert.assertEquals(result, messageWrapper.getMessage());

    MessageWrapper queryResult = queryStub.query();
    Assert.assertEquals(queryResult, messageWrapper);
  }

  @Test
  public void testQueryBoundOnParentInterface() {
    MessageWrapper messageWrapper = MessageWrapper.createRandom();

    QueryBoundWorkflow<?> stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(QueryBoundWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute, messageWrapper.getMessage());

    QueryBoundWorkflow<?> queryStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(QueryBoundWorkflow.class, execution.getWorkflowId());

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    Assert.assertEquals(result, messageWrapper.getMessage());

    MessageWrapper queryResult = queryStub.query();
    Assert.assertEquals(queryResult, messageWrapper);
  }

  @Test
  public void testQueryWithNoAnnotationAndOverride() {
    MessageWrapper messageWrapper = MessageWrapper.createRandom();

    QueryOverrideWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(QueryOverrideWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute, messageWrapper.getMessage());

    QueryOverrideWorkflow queryStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(QueryOverrideWorkflow.class, execution.getWorkflowId());

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    Assert.assertEquals(result, messageWrapper.getMessage());

    MessageWrapper queryResult = queryStub.query();
    Assert.assertEquals(queryResult, messageWrapper);
  }

  @Test
  public void testQueryOnParentAndGrandparentInterfaces() {
    MessageWrapper messageWrapper = MessageWrapper.createRandom();

    QueryExtensionWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(QueryExtensionWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute, messageWrapper.getMessage());

    QueryExtensionWorkflow queryStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(QueryExtensionWorkflow.class, execution.getWorkflowId());

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    Assert.assertEquals(result, messageWrapper.getMessage());

    MessageWrapper queryResult = queryStub.query();
    Assert.assertEquals(queryResult, messageWrapper);

    MessageWrapper queryOtherResult = queryStub.queryOther();
    Assert.assertEquals(queryOtherResult, messageWrapper);
  }

  @Test
  public void testSignalOnParentInterface() {
    SignalWorkflow stub = testWorkflowRule.newWorkflowStubTimeoutOptions(SignalWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute);

    SignalWorkflow signalStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(SignalWorkflow.class, execution.getWorkflowId());

    MessageWrapper messageWrapper = MessageWrapper.createRandom();
    signalStub.signal(messageWrapper);
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    Assert.assertEquals(result, messageWrapper.getMessage());
  }

  @Test
  public void testUpdateOnParentInterface() {
    UpdateWorkflow stub = testWorkflowRule.newWorkflowStubTimeoutOptions(UpdateWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute);

    UpdateWorkflow updateStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(UpdateWorkflow.class, execution.getWorkflowId());

    MessageWrapper messageWrapper = MessageWrapper.createRandom();
    MessageWrapper updateResult = updateStub.update(messageWrapper);
    Assert.assertEquals(updateResult, messageWrapper);

    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    Assert.assertEquals(result, messageWrapper.getMessage());
  }

  @Test
  public void testWorkflowBoundOnInterface() {
    MessageWrapper initialMessage = MessageWrapper.createRandom();

    WorkflowBound<?> stub = testWorkflowRule.newWorkflowStubTimeoutOptions(WorkflowBound.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute, initialMessage.getMessage());

    WorkflowBound<MessageWrapper> queryStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(WorkflowBound.class, execution.getWorkflowId());

    MessageWrapper queryMessage = MessageWrapper.createRandom();
    MessageWrapper queryResult = queryStub.query(queryMessage);
    Assert.assertEquals(queryMessage.concatenate(initialMessage), queryResult);

    MessageWrapper updateMessage = MessageWrapper.createRandom();
    MessageWrapper updateResult = queryStub.update(updateMessage);
    Assert.assertEquals(updateMessage.concatenate(initialMessage), updateResult);
    queryResult = queryStub.query(queryMessage);
    Assert.assertEquals(queryMessage.concatenate(updateMessage), queryResult);

    MessageWrapper signalMessage = MessageWrapper.createRandom();
    queryStub.signal(signalMessage);
    queryResult = queryStub.query(queryMessage);
    Assert.assertEquals(queryMessage.concatenate(signalMessage), queryResult);

    queryStub.signal(MessageWrapper.FINISH);
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    Assert.assertEquals(result, initialMessage.getMessage());
  }

  @Test
  public void testWorkflowWildcardBoundOnInterface() {
    GenericMessageWrapper<UUID> initialMessage = new GenericMessageWrapper<>(UUID.randomUUID());

    WorkflowWildcardBound stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(WorkflowWildcardBound.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute, initialMessage.getMessage());

    WorkflowWildcardBound<UUID> queryStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(WorkflowWildcardBound.class, execution.getWorkflowId());

    GenericMessageWrapper<? extends UUID> queryResult = queryStub.query();
    Assert.assertEquals(initialMessage, queryResult);

    GenericMessageWrapper<UUID> updateMessage = new GenericMessageWrapper<>(UUID.randomUUID());
    GenericMessageWrapper<? extends UUID> updateResult = queryStub.update(updateMessage);
    Assert.assertEquals(updateMessage, updateResult);
    queryResult = queryStub.query();
    Assert.assertEquals(updateMessage, queryResult);

    GenericMessageWrapper<UUID> signalMessage = new GenericMessageWrapper<>(UUID.randomUUID());
    queryStub.signal(signalMessage);
    queryResult = queryStub.query();
    Assert.assertEquals(signalMessage, queryResult);

    queryStub.signal(new GenericMessageWrapper<>(FINISH_UUID));
    UUID result = WorkflowStub.fromTyped(stub).getResult(UUID.class);
    Assert.assertEquals(result, initialMessage.getMessage());
  }

  public static UUID FINISH_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  public static class MessageWrapper extends GenericMessageWrapper<String> {
    public static final MessageWrapper FINISH = new MessageWrapper("FINISH");

    public static MessageWrapper createRandom() {
      return new MessageWrapper(UUID.randomUUID().toString());
    }

    @JsonCreator
    public MessageWrapper(@JsonProperty("message") String message) {
      super(message);
    }

    public MessageWrapper concatenate(MessageWrapper message) {
      return new MessageWrapper(getMessage() + ", " + message.getMessage());
    }

    public MessageWrapper concatenate(String message) {
      return new MessageWrapper(getMessage() + ", " + message);
    }
  }

  public static class GenericMessageWrapper<T> {
    private final T message;

    @JsonCreator
    public GenericMessageWrapper(@JsonProperty("message") T message) {
      this.message = message;
    }

    public T getMessage() {
      return message;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof GenericMessageWrapper)) {
        return false;
      }
      GenericMessageWrapper<?> that = (GenericMessageWrapper<?>) o;
      return Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(message);
    }

    @Override
    public String toString() {
      return message.toString();
    }
  }

  public interface ExecuteBase<T> {
    @WorkflowMethod
    T execute(T arg);
  }

  @WorkflowInterface
  public interface ExecuteWorkflow extends ExecuteBase<MessageWrapper> {}

  public static class ExecuteWorkflowImpl implements ExecuteWorkflow {
    @Override
    public MessageWrapper execute(MessageWrapper arg) {
      return arg;
    }
  }

  public interface ExecuteBaseTwoTypeVariables<T, U> {
    @WorkflowMethod
    T execute(U arg);
  }

  @WorkflowInterface
  public interface ExecuteTwoTypeVariablesWorkflow
      extends ExecuteBaseTwoTypeVariables<UUID, MessageWrapper> {}

  public static class ExecuteTwoTypeVariablesWorkflowImpl
      implements ExecuteTwoTypeVariablesWorkflow {
    @Override
    public UUID execute(MessageWrapper arg) {
      return UUID.fromString(arg.getMessage());
    }
  }

  public interface ExecuteBaseAndParameterizedTypeWithTypeVariable<T>
      extends ExecuteBase<GenericMessageWrapper<T>> {}

  @WorkflowInterface
  public interface ExecuteParameterizedTypeWithTypeVariableWorkflow
      extends ExecuteBaseAndParameterizedTypeWithTypeVariable<UUID> {}

  public static class ExecuteParameterizedTypeWithTypeVariableWorkflowImpl
      implements ExecuteParameterizedTypeWithTypeVariableWorkflow {
    @Override
    public GenericMessageWrapper<UUID> execute(GenericMessageWrapper<UUID> arg) {
      return arg;
    }
  }

  @WorkflowInterface
  public interface ExecuteParameterizedTypeWorkflow
      extends ExecuteBase<GenericMessageWrapper<UUID>> {}

  public static class ExecuteParameterizedTypeWorkflowImpl
      implements ExecuteParameterizedTypeWorkflow {
    @Override
    public GenericMessageWrapper<UUID> execute(GenericMessageWrapper<UUID> arg) {
      return arg;
    }
  }

  public interface ExecuteBaseAndGenericArrayType<T> extends ExecuteBase<T[]> {}

  @WorkflowInterface
  public interface ExecuteGenericArrayTypeWorkflow extends ExecuteBaseAndGenericArrayType<String> {}

  public static class ExecuteGenericArrayTypeWorkflowWorkflowImpl
      implements ExecuteGenericArrayTypeWorkflow {
    @Override
    public String[] execute(String[] arg) {
      return arg;
    }
  }

  public interface ExecuteBaseParameterizedGenericAndArrayTypeWorkflow<T>
      extends ExecuteBase<GenericMessageWrapper<T[]>> {}

  @WorkflowInterface
  public interface ExecuteParameterizedGenericAndArrayTypeWorkflow
      extends ExecuteBaseParameterizedGenericAndArrayTypeWorkflow<UUID> {}

  public static class ExecuteParameterizedGenericAndArrayTypeWorkflowImpl
      implements ExecuteParameterizedGenericAndArrayTypeWorkflow {

    @Override
    public GenericMessageWrapper<UUID[]> execute(GenericMessageWrapper<UUID[]> arg) {
      return arg;
    }
  }

  public interface QueryBase<T> {
    @QueryMethod
    T query();
  }

  @WorkflowInterface
  public interface QueryWorkflow extends QueryBase<MessageWrapper> {
    @WorkflowMethod
    String execute(String message);
  }

  @WorkflowInterface
  public interface QueryBoundWorkflow<T extends MessageWrapper> extends QueryBase<T> {
    @WorkflowMethod
    String execute(String message);
  }

  public static class QueryWorkflowImpl
      implements QueryWorkflow, QueryBoundWorkflow<MessageWrapper> {
    private String message;

    @Override
    public MessageWrapper query() {
      return new MessageWrapper(message);
    }

    @Override
    public String execute(String message) {
      this.message = message;
      return message;
    }
  }

  public interface QueryBaseAndNoAnnotation<T> {
    T query();
  }

  @WorkflowInterface
  public interface QueryOverrideWorkflow extends QueryBaseAndNoAnnotation<MessageWrapper> {
    @WorkflowMethod
    String execute(String message);

    @Override
    @QueryMethod
    MessageWrapper query();
  }

  public static class QueryOverrideWorkflowImpl implements QueryOverrideWorkflow {
    private String message;

    @Override
    public MessageWrapper query() {
      return new MessageWrapper(message);
    }

    @Override
    public String execute(String message) {
      this.message = message;
      return message;
    }
  }

  public interface QueryBaseExtension<T> extends QueryBase<T> {
    @QueryMethod
    T queryOther();
  }

  @WorkflowInterface
  public interface QueryExtensionWorkflow extends QueryBaseExtension<MessageWrapper> {
    @WorkflowMethod
    String execute(String message);
  }

  public static class QueryExtensionWorkflowImpl implements QueryExtensionWorkflow {
    private String message;

    @Override
    public MessageWrapper query() {
      return new MessageWrapper(message);
    }

    @Override
    public MessageWrapper queryOther() {
      return new MessageWrapper(message);
    }

    @Override
    public String execute(String message) {
      this.message = message;
      return message;
    }
  }

  @WorkflowInterface
  public interface WorkflowBound<T extends MessageWrapper> {
    @WorkflowMethod
    String execute(String message);

    @QueryMethod
    T query(T arg);

    @SignalMethod
    void signal(T arg);

    @UpdateMethod
    T update(T arg);
  }

  public static class WorkflowBoundImpl implements WorkflowBound<MessageWrapper> {
    private String message;

    @Override
    public String execute(String message) {
      this.message = message;
      Workflow.await(() -> MessageWrapper.FINISH.getMessage().equals(this.message));
      return message;
    }

    @Override
    public MessageWrapper query(MessageWrapper arg) {
      return arg.concatenate(message);
    }

    @SignalMethod
    public void signal(MessageWrapper arg) {
      this.message = arg.getMessage();
    }

    @UpdateMethod
    public MessageWrapper update(MessageWrapper arg) {
      MessageWrapper result = arg.concatenate(message);
      this.message = arg.getMessage();
      return result;
    }
  }

  @WorkflowInterface
  public interface WorkflowWildcardBound<T extends UUID> {
    @WorkflowMethod
    UUID execute(UUID message);

    @QueryMethod
    GenericMessageWrapper<? extends T> query();

    @SignalMethod
    void signal(GenericMessageWrapper<? extends T> arg);

    @UpdateMethod
    GenericMessageWrapper<? extends T> update(GenericMessageWrapper<? extends T> arg);
  }

  public static class WorkflowWildcardBoundImpl implements WorkflowWildcardBound<UUID> {
    private UUID message;

    @Override
    public UUID execute(UUID message) {
      this.message = message;
      Workflow.await(() -> FINISH_UUID.equals(this.message));
      return message;
    }

    @Override
    public GenericMessageWrapper<? extends UUID> query() {
      return new GenericMessageWrapper<>(message);
    }

    @SignalMethod
    public void signal(GenericMessageWrapper<? extends UUID> arg) {
      this.message = arg.getMessage();
    }

    @UpdateMethod
    public GenericMessageWrapper<? extends UUID> update(GenericMessageWrapper<? extends UUID> arg) {
      signal(arg);
      return query();
    }
  }

  public interface SignalBase<T> {
    @SignalMethod
    void signal(T signal);
  }

  @WorkflowInterface
  public interface SignalWorkflow extends SignalBase<MessageWrapper> {
    @WorkflowMethod
    String execute();
  }

  public static class SignalWorkflowImpl implements SignalWorkflow {
    private String message;

    @Override
    public void signal(MessageWrapper signal) {
      this.message = signal.getMessage();
    }

    @Override
    public String execute() {
      Workflow.await(() -> message != null);
      return message;
    }
  }

  public interface UpdateBase<T> {
    @UpdateMethod
    T update(T value);

    @UpdateValidatorMethod(updateName = "update")
    void updateValidator(T value);
  }

  @WorkflowInterface
  public interface UpdateWorkflow extends UpdateBase<MessageWrapper> {
    @WorkflowMethod
    String execute();
  }

  public static class UpdateWorkflowImpl implements UpdateWorkflow {
    private String message;

    @Override
    public String execute() {
      Workflow.await(() -> message != null);
      return message;
    }

    @Override
    public MessageWrapper update(MessageWrapper value) {
      this.message = value.getMessage();
      return value;
    }

    @Override
    public void updateValidator(MessageWrapper value) {
      if (value == null) {
        throw new IllegalArgumentException("Invalid update!");
      }
    }
  }
}
