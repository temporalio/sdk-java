package io.temporal.common.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class DataConverterGenericTypeTest {

  @Test
  public void testFromPayloadsWithGenericListParameter() throws Exception {
    DataConverter dataConverter = DefaultDataConverter.newDefaultInstance();
    Method method = TestInterface.class.getMethod("processData", String.class, List.class);
    Optional<Payloads> payloads = dataConverter.toPayloads("test-data");

    Object[] args =
        dataConverter.fromPayloads(
            payloads, method.getParameterTypes(), method.getGenericParameterTypes());

    assertEquals(2, args.length);
    assertEquals("test-data", args[0]);
    assertNull(args[1]);
  }

  @Test
  public void testFromPayloadsWithEmptyPayloads() throws Exception {
    DataConverter dataConverter = DefaultDataConverter.newDefaultInstance();
    Method method = TestInterface.class.getMethod("processData", String.class, List.class);
    Object[] args =
        dataConverter.fromPayloads(
            Optional.empty(), method.getParameterTypes(), method.getGenericParameterTypes());

    assertEquals(2, args.length);
    assertNull(args[0]);
    assertNull(args[1]);
  }

  public interface TestInterface {
    void processData(String data, List<String> items);
  }
}
