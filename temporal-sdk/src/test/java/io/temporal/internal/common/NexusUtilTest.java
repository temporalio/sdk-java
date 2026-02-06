package io.temporal.internal.common;

import io.nexusrpc.FailureInfo;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import org.junit.Assert;
import org.junit.Test;

public class NexusUtilTest {
  private static final DataConverter DATA_CONVERTER = DefaultDataConverter.STANDARD_INSTANCE;

  @Test
  public void testParseRequestTimeout() {
    Assert.assertThrows(
        IllegalArgumentException.class, () -> NexusUtil.parseRequestTimeout("invalid"));
    Assert.assertThrows(IllegalArgumentException.class, () -> NexusUtil.parseRequestTimeout("1h"));
    Assert.assertEquals(java.time.Duration.ofMillis(10), NexusUtil.parseRequestTimeout("10ms"));
    Assert.assertEquals(java.time.Duration.ofMillis(10), NexusUtil.parseRequestTimeout("10.1ms"));
    Assert.assertEquals(java.time.Duration.ofSeconds(1), NexusUtil.parseRequestTimeout("1s"));
    Assert.assertEquals(java.time.Duration.ofMinutes(999), NexusUtil.parseRequestTimeout("999m"));
    Assert.assertEquals(java.time.Duration.ofMillis(1300), NexusUtil.parseRequestTimeout("1.3s"));
  }

  @Test
  public void testTemporalFailureToNexusFailureRoundTrip() {
    // Create a Temporal failure with details
    Failure temporalFailure =
        Failure.newBuilder()
            .setMessage("test failure")
            .setStackTrace("at test.Class.method(Class.java:123)")
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder()
                    .setType("TestFailure")
                    .setNonRetryable(true)
                    .build())
            .build();

    // Convert to Nexus failure
    io.temporal.api.nexus.v1.Failure nexusFailure =
        NexusUtil.temporalFailureToNexusFailure(temporalFailure);

    // Verify message is preserved
    Assert.assertEquals("test failure", nexusFailure.getMessage());

    // Verify metadata indicates this is a Temporal failure
    Assert.assertTrue(nexusFailure.getMetadataMap().containsKey("type"));
    Assert.assertEquals(
        "temporal.api.failure.v1.Failure", nexusFailure.getMetadataMap().get("type"));

    // Convert back via FailureInfo
    FailureInfo.Builder failureInfoBuilder =
        FailureInfo.newBuilder()
            .setMessage(nexusFailure.getMessage())
            .setDetailsJson(nexusFailure.getDetails().toStringUtf8())
            .setStackTrace(nexusFailure.getStackTrace());
    // Add metadata entries individually
    for (String key : nexusFailure.getMetadataMap().keySet()) {
      failureInfoBuilder.putMetadata(key, nexusFailure.getMetadataMap().get(key));
    }
    FailureInfo failureInfo = failureInfoBuilder.build();

    Failure reconstructed = NexusUtil.nexusFailureToAPIFailure(failureInfo, true);

    // Verify round-trip preserves all fields
    Assert.assertEquals("test failure", reconstructed.getMessage());
    Assert.assertEquals("at test.Class.method(Class.java:123)", reconstructed.getStackTrace());
    Assert.assertTrue(reconstructed.hasApplicationFailureInfo());
    Assert.assertEquals("TestFailure", reconstructed.getApplicationFailureInfo().getType());
    Assert.assertTrue(reconstructed.getApplicationFailureInfo().getNonRetryable());
  }

  @Test
  public void testTemporalFailureToNexusFailureInfoRoundTrip() {
    // Create a Temporal failure with nested cause
    Failure innerFailure =
        Failure.newBuilder()
            .setMessage("inner cause")
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setType("InnerFailure").build())
            .build();

    Failure outerFailure =
        Failure.newBuilder()
            .setMessage("outer failure")
            .setCause(innerFailure)
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setType("OuterFailure").build())
            .build();

    // Convert to FailureInfo and back
    FailureInfo failureInfo = NexusUtil.temporalFailureToNexusFailureInfo(outerFailure);
    Failure reconstructed = NexusUtil.nexusFailureToAPIFailure(failureInfo, false);

    // Verify nested structure is preserved
    Assert.assertEquals("outer failure", reconstructed.getMessage());
    Assert.assertEquals("OuterFailure", reconstructed.getApplicationFailureInfo().getType());
    Assert.assertTrue(reconstructed.hasCause());
    Assert.assertEquals("inner cause", reconstructed.getCause().getMessage());
    Assert.assertEquals(
        "InnerFailure", reconstructed.getCause().getApplicationFailureInfo().getType());
  }

  @Test
  public void testHandlerErrorToNexusErrorWithCause() {
    ApplicationFailure cause = ApplicationFailure.newFailure("test error", "TestType", "detail");
    HandlerException exception =
        new HandlerException(HandlerException.ErrorType.BAD_REQUEST, cause);

    HandlerError nexusError = NexusUtil.handlerErrorToNexusError(exception, DATA_CONVERTER);

    Assert.assertEquals("BAD_REQUEST", nexusError.getErrorType());
    Assert.assertTrue(nexusError.hasFailure());
    Assert.assertEquals("test error", nexusError.getFailure().getMessage());
  }

  @Test
  public void testHandlerErrorToNexusErrorWithoutCause() {
    HandlerException exception =
        new HandlerException(
            HandlerException.ErrorType.BAD_REQUEST, "handler message", (Throwable) null);

    HandlerError nexusError = NexusUtil.handlerErrorToNexusError(exception, DATA_CONVERTER);

    Assert.assertEquals("BAD_REQUEST", nexusError.getErrorType());
    Assert.assertTrue(nexusError.hasFailure());
    Assert.assertEquals("handler message", nexusError.getFailure().getMessage());
  }

  @Test
  public void testHandlerErrorToNexusErrorWithEmptyMessage() {
    HandlerException exception =
        new HandlerException(HandlerException.ErrorType.INTERNAL, "", (Throwable) null);

    HandlerError nexusError = NexusUtil.handlerErrorToNexusError(exception, DATA_CONVERTER);

    Assert.assertEquals("INTERNAL", nexusError.getErrorType());
    // Should not have failure when message is empty
    Assert.assertFalse(nexusError.hasFailure());
  }

  @Test
  public void testNexusFailureWithStackTracePreservation() {
    String stackTrace =
        "at io.temporal.test.Method1(Test.java:100)\n"
            + "at io.temporal.test.Method2(Test.java:200)\n"
            + "at io.temporal.test.Method3(Test.java:300)";

    Failure failure =
        Failure.newBuilder()
            .setMessage("failure with stack")
            .setStackTrace(stackTrace)
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setType("TestFailure").build())
            .build();

    io.temporal.api.nexus.v1.Failure nexusFailure =
        NexusUtil.temporalFailureToNexusFailure(failure);
    Assert.assertEquals(stackTrace, nexusFailure.getStackTrace());

    // Convert back
    FailureInfo.Builder failureInfoBuilder =
        FailureInfo.newBuilder()
            .setMessage(nexusFailure.getMessage())
            .setDetailsJson(nexusFailure.getDetails().toStringUtf8())
            .setStackTrace(nexusFailure.getStackTrace());
    // Add metadata entries individually
    for (String key : nexusFailure.getMetadataMap().keySet()) {
      failureInfoBuilder.putMetadata(key, nexusFailure.getMetadataMap().get(key));
    }
    FailureInfo failureInfo = failureInfoBuilder.build();

    Failure reconstructed = NexusUtil.nexusFailureToAPIFailure(failureInfo, true);
    Assert.assertEquals(stackTrace, reconstructed.getStackTrace());
  }

  @Test
  public void testNexusFailureWithoutTemporalMetadata() {
    // Test handling of non-Temporal Nexus failures
    FailureInfo failureInfo =
        FailureInfo.newBuilder()
            .setMessage("generic nexus failure")
            .putMetadata("custom-key", "custom-value")
            .setDetailsJson("{\"detail\":\"some data\"}")
            .build();

    Failure apiFailure = NexusUtil.nexusFailureToAPIFailure(failureInfo, true);

    // Should be wrapped as NexusFailure type
    Assert.assertEquals("generic nexus failure", apiFailure.getMessage());
    Assert.assertTrue(apiFailure.hasApplicationFailureInfo());
    Assert.assertEquals("NexusFailure", apiFailure.getApplicationFailureInfo().getType());
    Assert.assertFalse(apiFailure.getApplicationFailureInfo().getNonRetryable());
  }

  @Test
  public void testDeeplyNestedFailureCauses() {
    // Test 4 levels of nesting
    Failure level4 =
        Failure.newBuilder()
            .setMessage("level 4")
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setType("Level4").build())
            .build();

    Failure level3 =
        Failure.newBuilder()
            .setMessage("level 3")
            .setCause(level4)
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setType("Level3").build())
            .build();

    Failure level2 =
        Failure.newBuilder()
            .setMessage("level 2")
            .setCause(level3)
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setType("Level2").build())
            .build();

    Failure level1 =
        Failure.newBuilder()
            .setMessage("level 1")
            .setCause(level2)
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setType("Level1").build())
            .build();

    // Convert through Nexus format and back
    io.temporal.api.nexus.v1.Failure nexusFailure = NexusUtil.temporalFailureToNexusFailure(level1);
    FailureInfo failureInfo = NexusUtil.temporalFailureToNexusFailureInfo(level1);
    Failure reconstructed = NexusUtil.nexusFailureToAPIFailure(failureInfo, true);

    // Verify all levels are preserved
    Assert.assertEquals("level 1", reconstructed.getMessage());
    Assert.assertEquals("level 2", reconstructed.getCause().getMessage());
    Assert.assertEquals("level 3", reconstructed.getCause().getCause().getMessage());
    Assert.assertEquals("level 4", reconstructed.getCause().getCause().getCause().getMessage());
  }

  @Test
  public void testCanceledFailureConversion() {
    Failure canceledFailure =
        Failure.newBuilder()
            .setMessage("operation canceled")
            .setCanceledFailureInfo(CanceledFailureInfo.newBuilder().build())
            .build();

    FailureInfo failureInfo = NexusUtil.temporalFailureToNexusFailureInfo(canceledFailure);
    Failure reconstructed = NexusUtil.nexusFailureToAPIFailure(failureInfo, true);

    Assert.assertEquals("operation canceled", reconstructed.getMessage());
    Assert.assertTrue(reconstructed.hasCanceledFailureInfo());
  }
}
