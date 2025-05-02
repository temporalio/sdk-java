package io.temporal.failure;

import org.junit.Assert;
import org.junit.Test;

public class ApplicationFailureTest {

  @Test
  public void applicationFailureCopy() {
    ApplicationFailure originalAppFailure =
        ApplicationFailure.newBuilder().setType("TestType").setMessage("test message").build();
    ApplicationFailure newAppFailure =
        ApplicationFailure.newBuilder(originalAppFailure).setNonRetryable(true).build();
    Assert.assertEquals(originalAppFailure.getType(), newAppFailure.getType());
    Assert.assertEquals(
        originalAppFailure.getOriginalMessage(), newAppFailure.getOriginalMessage());
    Assert.assertNotEquals(originalAppFailure.isNonRetryable(), newAppFailure.isNonRetryable());
  }
}
