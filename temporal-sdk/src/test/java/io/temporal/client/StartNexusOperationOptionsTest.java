package io.temporal.client;

import org.junit.Assert;
import org.junit.Test;

/**
 * Pure unit tests for {@link StartNexusOperationOptions.Builder} input validation. ID is required —
 * callers must supply a non-blank value via {@link StartNexusOperationOptions.Builder#setId} and
 * the SDK does not invent one on their behalf.
 */
public class StartNexusOperationOptionsTest {

  @Test
  public void buildThrowsWhenIdNotSet() {
    try {
      StartNexusOperationOptions.newBuilder().build();
      Assert.fail("expected IllegalStateException when id is unset");
    } catch (IllegalStateException expected) {
      Assert.assertTrue(
          "error message should mention setId, got: " + expected.getMessage(),
          expected.getMessage() != null && expected.getMessage().contains("setId"));
    }
  }

  @Test
  public void setIdRejectsNull() {
    try {
      StartNexusOperationOptions.newBuilder().setId(null);
      Assert.fail("expected NullPointerException when setId is called with null");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  @Test
  public void setIdRejectsEmpty() {
    try {
      StartNexusOperationOptions.newBuilder().setId("");
      Assert.fail("expected IllegalArgumentException when setId is called with an empty string");
    } catch (IllegalArgumentException expected) {
      Assert.assertTrue(
          "error message should mention blank, got: " + expected.getMessage(),
          expected.getMessage() != null && expected.getMessage().contains("blank"));
    }
  }

  @Test
  public void setIdRejectsWhitespaceOnly() {
    try {
      StartNexusOperationOptions.newBuilder().setId("   \t  ");
      Assert.fail(
          "expected IllegalArgumentException when setId is called with a whitespace-only id");
    } catch (IllegalArgumentException expected) {
      Assert.assertTrue(
          "error message should mention blank, got: " + expected.getMessage(),
          expected.getMessage() != null && expected.getMessage().contains("blank"));
    }
  }

  @Test
  public void buildSucceedsWithNonEmptyId() {
    StartNexusOperationOptions options =
        StartNexusOperationOptions.newBuilder().setId("my-id").build();
    Assert.assertEquals("my-id", options.getId());
  }
}
