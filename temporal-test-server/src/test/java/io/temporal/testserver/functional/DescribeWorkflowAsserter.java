package io.temporal.testserver.functional;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Timestamps;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.RequestIdInfo;
import io.temporal.api.workflow.v1.WorkflowExecutionConfig;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assert;

/*
 * Fluent assertions (ala truth or assert-j) for DescribeWorkflowResults.
 */
final class DescribeWorkflowAsserter {

  private final DescribeWorkflowExecutionResponse actual;

  public DescribeWorkflowAsserter(DescribeWorkflowExecutionResponse actual) {
    this.actual = actual;
  }

  public DescribeWorkflowExecutionResponse getActual() {
    return actual;
  }

  public DescribeWorkflowAsserter assertMatchesOptions(WorkflowOptions options) {
    WorkflowExecutionConfig ec = actual.getExecutionConfig();
    Assert.assertEquals(
        "workflow execution timeout should match",
        options.getWorkflowExecutionTimeout(),
        ProtobufTimeUtils.toJavaDuration(ec.getWorkflowExecutionTimeout()));
    Assert.assertEquals(
        "workflow run timeout should match",
        options.getWorkflowRunTimeout(),
        ProtobufTimeUtils.toJavaDuration(ec.getWorkflowRunTimeout()));
    Assert.assertEquals(
        "workflow task timeout should match",
        options.getWorkflowTaskTimeout(),
        ProtobufTimeUtils.toJavaDuration(ec.getDefaultWorkflowTaskTimeout()));

    WorkflowExecutionInfo ei = actual.getWorkflowExecutionInfo();
    Assert.assertEquals(
        "memo should match", options.getMemo(), toSimpleMap(ei.getMemo().getFieldsMap()));
    return this;
  }

  private Map<String, Object> toSimpleMap(Map<String, Payload> payloadMap) {
    return payloadMap.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> payloadToString(e.getValue())));
  }

  private String payloadToString(Payload payload) {
    // For simplicity, we only test with strings in the payload, which get serialized as json
    String jsonPayload = payload.getData().toStringUtf8();
    Preconditions.checkState(
        jsonPayload.startsWith("\"") && jsonPayload.endsWith("\""),
        "Payload not a json string: %s",
        jsonPayload);
    // Strip off the quotes to get the original string
    return jsonPayload.substring(1, jsonPayload.length() - 1);
  }

  public static Payloads stringsToPayloads(String... strings) {
    Payloads.Builder payloadsBuilder = Payloads.newBuilder();

    for (String s : strings) {
      payloadsBuilder.addPayloads(
          Payload.newBuilder()
              .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
              .setData(ByteString.copyFromUtf8("\"" + s + "\""))
              .build());
    }

    return payloadsBuilder.build();
  }

  public DescribeWorkflowAsserter assertTaskQueue(String expected) {
    Assert.assertEquals(
        "task queue should match", expected, actual.getExecutionConfig().getTaskQueue().getName());

    // There's a task queue in WorkflowExecutionInfo too, but the golang doesn't set it, so we don't
    // either.

    return this;
  }

  public DescribeWorkflowAsserter assertSaneTimestamps() {
    WorkflowExecutionInfo ei = actual.getWorkflowExecutionInfo();

    if (ei.hasStartTime()) {
      Assert.assertTrue("start time should be positive", ei.getStartTime().getSeconds() > 0);

      Assert.assertTrue(
          "start time should be <= execution time",
          Timestamps.compare(ei.getStartTime(), ei.getExecutionTime()) <= 0);

      if (ei.hasCloseTime()) {
        Assert.assertTrue(
            "start time should be <= close time",
            Timestamps.compare(ei.getStartTime(), ei.getCloseTime()) <= 0);
      }
    }

    return this;
  }

  public DescribeWorkflowAsserter assertExecutionId(WorkflowExecution expected) {
    Assert.assertEquals(
        "execution should match", expected, actual.getWorkflowExecutionInfo().getExecution());
    return this;
  }

  public DescribeWorkflowAsserter assertType(String expected) {
    Assert.assertEquals(
        "workflow type should match",
        expected,
        actual.getWorkflowExecutionInfo().getType().getName());
    return this;
  }

  public DescribeWorkflowAsserter assertStatus(WorkflowExecutionStatus expected) {
    Assert.assertEquals(
        "status should match", expected, actual.getWorkflowExecutionInfo().getStatus());
    return this;
  }

  public DescribeWorkflowAsserter assertNoParent() {
    WorkflowExecutionInfo ei = actual.getWorkflowExecutionInfo();
    Assert.assertEquals("parent namespace should be absent", "", ei.getParentNamespaceId());
    Assert.assertFalse("parent execution should be absent", ei.hasParentExecution());
    return this;
  }

  public DescribeWorkflowAsserter assertNoExecutionDuration() {
    WorkflowExecutionInfo ei = actual.getWorkflowExecutionInfo();
    Assert.assertFalse("execution duration should be absent", ei.hasExecutionDuration());
    return this;
  }

  public DescribeWorkflowAsserter assertHasExecutionDuration() {
    WorkflowExecutionInfo ei = actual.getWorkflowExecutionInfo();
    Assert.assertTrue("execution duration should be present", ei.hasExecutionDuration());
    return this;
  }

  public DescribeWorkflowAsserter assertRoot(WorkflowExecution rootExec) {
    WorkflowExecutionInfo ei = actual.getWorkflowExecutionInfo();
    Assert.assertEquals(
        "root execution workflow id",
        rootExec.getWorkflowId(),
        ei.getRootExecution().getWorkflowId());
    Assert.assertEquals(
        "root execution run id", rootExec.getRunId(), ei.getRootExecution().getRunId());
    return this;
  }

  public DescribeWorkflowAsserter assertFirstRunId(String runId) {
    WorkflowExecutionInfo ei = actual.getWorkflowExecutionInfo();
    Assert.assertEquals("first run id should match", runId, ei.getFirstRunId());
    return this;
  }

  public DescribeWorkflowAsserter assertParent(WorkflowExecution parentExecution) {
    WorkflowExecutionInfo ei = actual.getWorkflowExecutionInfo();
    // We don't assert parent namespace because we need the _id_, not the name,
    // and DescribeNamespace isn't implemented in the test service.
    Assert.assertEquals("parent execution should match", parentExecution, ei.getParentExecution());
    return this;
  }

  public DescribeWorkflowAsserter assertPendingActivityCount(int expected) {
    Assert.assertEquals(
        "pending activity count should match", expected, actual.getPendingActivitiesCount());
    return this;
  }

  public DescribeWorkflowAsserter assertPendingChildrenCount(int expected) {
    Assert.assertEquals(
        "child workflow count should match", expected, actual.getPendingChildrenCount());
    return this;
  }

  public DescribeWorkflowAsserter assertRequestIdInfos(Map<String, RequestIdInfo> expected) {
    Assert.assertEquals(
        "request id infos should match",
        expected,
        actual.getWorkflowExtendedInfo().getRequestIdInfosMap());
    return this;
  }
}
