package io.temporal.workflow.searchattributes;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.internal.client.WorkflowClientHelper;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

@SuppressWarnings("deprecation")
public class SearchAttributesTest {
  private static final String TEST_KEY_STRING = "CustomStringField";
  private static final String TEST_VALUE_STRING = NAMESPACE;
  private static final String TEST_KEY_INTEGER = "CustomIntField";
  private static final Long TEST_VALUE_LONG = 7L;
  private static final String TEST_KEY_DATE_TIME = "CustomDatetimeField";
  private static final OffsetDateTime TEST_VALUE_DATE_TIME = OffsetDateTime.now();
  private static final String TEST_KEY_DOUBLE = "CustomDoubleField";
  private static final Double TEST_VALUE_DOUBLE = 1.23;
  private static final String TEST_KEY_BOOL = "CustomBoolField";
  private static final Boolean TEST_VALUE_BOOL = true;
  private static final String TEST_NEW_KEY = "NewKey";
  private static final String TEST_NEW_VALUE = "NewVal";
  private static final String TEST_UNKNOWN_KEY = "UnknownKey";

  private static final Map<String, Object> DEFAULT_SEARCH_ATTRIBUTES =
      ImmutableMap.of(
          TEST_KEY_STRING,
          TEST_VALUE_STRING,
          TEST_KEY_INTEGER,
          TEST_VALUE_LONG,
          TEST_KEY_DOUBLE,
          TEST_VALUE_DOUBLE,
          TEST_KEY_BOOL,
          TEST_VALUE_BOOL,
          TEST_KEY_DATE_TIME,
          TEST_VALUE_DATE_TIME);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class, TestParentWorkflow.class, TestChild.class)
          .registerSearchAttribute(TEST_NEW_KEY, IndexedValueType.INDEXED_VALUE_TYPE_TEXT)
          .build();

  @Test
  public void defaultTestSearchAttributes() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setSearchAttributes(DEFAULT_SEARCH_ATTRIBUTES)
            .build();

    TestSignaledWorkflow stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::execute);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, List<?>> fieldsMap = SearchAttributesUtil.decode(searchAttrFromEvent);
    assertTrue(Maps.difference(wrapValues(DEFAULT_SEARCH_ATTRIBUTES), fieldsMap).areEqual());
  }

  @Test
  public void defaultTestSearchAttributesSignalWithStart() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setSearchAttributes(DEFAULT_SEARCH_ATTRIBUTES)
            .build();

    TestSignaledWorkflow stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);

    BatchRequest batchRequest = testWorkflowRule.getWorkflowClient().newSignalWithStartRequest();
    batchRequest.add(stubF::execute);
    batchRequest.add(stubF::signal, "signal");
    WorkflowExecution execution =
        testWorkflowRule.getWorkflowClient().signalWithStart(batchRequest);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            execution,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, List<?>> fieldsMap = SearchAttributesUtil.decode(searchAttrFromEvent);
    assertTrue(Maps.difference(wrapValues(DEFAULT_SEARCH_ATTRIBUTES), fieldsMap).areEqual());
  }

  @Test
  @Ignore("Fails on CLI release")
  public void testListInDefaultTestSearchAttributes() {
    Map<String, Object> searchAttributes = new HashMap<>(DEFAULT_SEARCH_ATTRIBUTES);
    searchAttributes.replace(TEST_KEY_INTEGER, Lists.newArrayList(1L, 2L));

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();

    TestSignaledWorkflow stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::execute);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, List<?>> fieldsMap = SearchAttributesUtil.decode(searchAttrFromEvent);
    assertTrue(Maps.difference(wrapValues(searchAttributes), fieldsMap).areEqual());
  }

  @Test
  public void testCustomSearchAttributes() {
    Map<String, Object> searchAttributes = new HashMap<>(DEFAULT_SEARCH_ATTRIBUTES);
    searchAttributes.put(TEST_NEW_KEY, TEST_NEW_VALUE);

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();

    TestSignaledWorkflow stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::execute);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, List<?>> fieldsMap = SearchAttributesUtil.decode(searchAttrFromEvent);
    assertTrue(Maps.difference(wrapValues(searchAttributes), fieldsMap).areEqual());
  }

  @Test
  public void testInvalidSearchAttributeKey() {
    Map<String, Object> searchAttributes = new HashMap<>(DEFAULT_SEARCH_ATTRIBUTES);
    searchAttributes.put(TEST_UNKNOWN_KEY, TEST_VALUE_BOOL);
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();
    TestSignaledWorkflow unregisteredKeyStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    try {
      WorkflowClient.start(unregisteredKeyStub::execute);
      fail();
    } catch (WorkflowServiceException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
      assertEquals(Status.Code.INVALID_ARGUMENT, sre.getStatus().getCode());
    }
  }

  @Test
  public void testInvalidSearchAttributeType() {
    Map<String, Object> searchAttributes = new HashMap<>(DEFAULT_SEARCH_ATTRIBUTES);
    searchAttributes.replace(TEST_KEY_INTEGER, TEST_VALUE_BOOL);
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();
    TestSignaledWorkflow unsupportedTypeStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    try {
      WorkflowClient.start(unsupportedTypeStub::execute);
      fail();
    } catch (WorkflowServiceException exception) {
      assertTrue(exception.getCause() instanceof StatusRuntimeException);
      StatusRuntimeException e = (StatusRuntimeException) exception.getCause();
      assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
    }
  }

  @Test
  public void testSearchAttributesPresentInChildWorkflow() {
    NoArgsWorkflow client = testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    client.execute();
  }

  private static Map<String, List<?>> wrapValues(Map<String, Object> map) {
    return Maps.transformValues(
        map,
        o ->
            o instanceof Collection
                ? new ArrayList<>((Collection<?>) o)
                : Collections.singletonList(o));
  }

  public static class TestWorkflowImpl implements TestSignaledWorkflow {
    @Override
    public String execute() {
      return "done";
    }

    @Override
    public void signal(String arg) {}
  }

  @WorkflowInterface
  public interface TestChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestParentWorkflow implements NoArgsWorkflow {
    @Override
    public void execute() {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setSearchAttributes(DEFAULT_SEARCH_ATTRIBUTES).build();
      TestChildWorkflow child = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      child.execute();
    }
  }

  public static class TestChild implements TestChildWorkflow {
    @Override
    public void execute() {
      // Check that search attributes are inherited by child workflows.
      assertEquals(wrapValues(DEFAULT_SEARCH_ATTRIBUTES), Workflow.getSearchAttributes());
    }
  }
}
