package io.temporal.common.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.reflect.TypeToken;
import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.ApplicationFailure;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PayloadValidationExceptionTest {
  @Test
  public void createReturnsNonRetryableApplicationFailureWithEncodedViolations() {
    List<Map<String, String>> violations =
        Collections.singletonList(Collections.singletonMap("path", "$.customer.contact.email"));

    ApplicationFailure applicationFailure = PayloadValidationException.create(violations);

    assertEquals("PayloadValidationError", applicationFailure.getType());
    assertTrue(applicationFailure.isNonRetryable());
    assertEquals("Payload validation failed", applicationFailure.getOriginalMessage());
    assertEquals(1, applicationFailure.getDetails().getSize());

    DataConverter dataConverter = DefaultDataConverter.STANDARD_INSTANCE;
    Failure encodedFailure = dataConverter.exceptionToFailure(applicationFailure);
    assertEquals(1, encodedFailure.getApplicationFailureInfo().getDetails().getPayloadsCount());

    ApplicationFailure decodedFailure =
        (ApplicationFailure) dataConverter.failureToException(encodedFailure);
    TypeToken<List<Map<String, String>>> violationsType =
        new TypeToken<List<Map<String, String>>>() {};
    assertEquals(
        violations, decodedFailure.getDetails().get(0, List.class, violationsType.getType()));
  }
}
