package io.temporal.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.time.Duration;
import org.junit.Test;

public class RetryOptionsTest {

  @Test
  public void mergeEmptyOptionsPreservesUnsetFields() {
    RetryOptions merged =
        RetryOptions.newBuilder().build().merge(RetryOptions.newBuilder().build());

    assertNull(merged.getInitialInterval());
    assertEquals(0.0, merged.getBackoffCoefficient(), 0.0);
    assertNull(merged.getMaximumInterval());
    assertNull(merged.getDoNotRetry());
    assertEquals(0, merged.getMaximumAttempts());
    assertEquals(
        RetryOptions.newBuilder().validateBuildWithDefaults(),
        merged.toBuilder().validateBuildWithDefaults());
  }

  @Test
  public void mergePartialOptionsCombinesExplicitFields() {
    RetryOptions original = RetryOptions.newBuilder().setMaximumAttempts(3).build();
    RetryOptions override = RetryOptions.newBuilder().setDoNotRetry("PermanentFailure").build();

    RetryOptions merged = original.merge(override);

    assertEquals(3, merged.getMaximumAttempts());
    assertArrayEquals(new String[] {"PermanentFailure"}, merged.getDoNotRetry());
    assertNull(merged.getInitialInterval());
    assertEquals(0.0, merged.getBackoffCoefficient(), 0.0);
    assertEquals(
        Duration.ofSeconds(1), merged.toBuilder().validateBuildWithDefaults().getInitialInterval());
  }

  @Test
  public void mergeInitialIntervalWithoutBackoff() {
    RetryOptions original =
        RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(3)).build();
    RetryOptions merged = original.merge(RetryOptions.newBuilder().setMaximumAttempts(2).build());

    assertEquals(Duration.ofSeconds(3), merged.getInitialInterval());
    assertEquals(0.0, merged.getBackoffCoefficient(), 0.0);
    assertEquals(2.0, merged.toBuilder().validateBuildWithDefaults().getBackoffCoefficient(), 0.0);
  }

  @Test
  public void mergeExplicitBackoffWithoutInitialInterval() {
    RetryOptions merged =
        RetryOptions.newBuilder()
            .setBackoffCoefficient(3.0)
            .build()
            .merge(RetryOptions.newBuilder().setBackoffCoefficient(4.0).build());

    assertNull(merged.getInitialInterval());
    assertEquals(4.0, merged.getBackoffCoefficient(), 0.0);
  }

  @Test
  public void mergeExplicitFieldsPrefersOverride() {
    RetryOptions original =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(2))
            .setMaximumInterval(Duration.ofSeconds(20))
            .setBackoffCoefficient(3.0)
            .setMaximumAttempts(5)
            .setDoNotRetry("OriginalFailure")
            .build();
    RetryOptions override =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(4))
            .setMaximumInterval(Duration.ofSeconds(40))
            .setBackoffCoefficient(4.0)
            .setMaximumAttempts(6)
            .setDoNotRetry("OverrideFailure")
            .build();

    assertEquals(override, original.merge(override));
    assertEquals(original, original.merge(RetryOptions.newBuilder().build()));
    assertSame(original, original.merge((RetryOptions) null));
  }

  @Test
  public void mergePrefersTheParameter() {
    RetryOptions o1 =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .validateBuildWithDefaults();
    RetryOptions o2 =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(2))
            .validateBuildWithDefaults();

    assertEquals(Duration.ofSeconds(2), o1.merge(o2).getInitialInterval());
  }

  @Test(expected = IllegalStateException.class)
  public void maximumIntervalCantBeLessThanInitial() {
    RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(5))
        .setMaximumInterval(Duration.ofSeconds(1))
        .validateBuildWithDefaults();
  }

  @Test
  public void mergePartialOptionsPreservesAnnotationValues() throws NoSuchMethodException {
    MethodRetry annotation =
        RetryOptionsTest.class.getMethod("annotatedRetry").getAnnotation(MethodRetry.class);
    RetryOptions merged =
        RetryOptions.newBuilder()
            .setMaximumAttempts(3)
            .build()
            .merge(RetryOptions.newBuilder().setDoNotRetry("PermanentFailure").build());

    RetryOptions resolved =
        RetryOptions.merge(annotation, merged).toBuilder().validateBuildWithDefaults();

    assertEquals(Duration.ofSeconds(5), resolved.getInitialInterval());
    assertEquals(4.0, resolved.getBackoffCoefficient(), 0.0);
    assertEquals(3, resolved.getMaximumAttempts());
    assertArrayEquals(new String[] {"PermanentFailure"}, resolved.getDoNotRetry());
  }

  @MethodRetry(initialIntervalSeconds = 5, backoffCoefficient = 4.0, maximumAttempts = 10)
  public void annotatedRetry() {}
}
