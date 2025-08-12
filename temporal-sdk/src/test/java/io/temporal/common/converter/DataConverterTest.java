package io.temporal.common.converter;

import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class DataConverterTest {
  // Test methods for reflection
  public String testMethodNormalParameter(String input, String names) {
    return "";
  }

  public String testMethodGenericParameter(String input, List<String> names) {
    return "";
  }

  public String testMethodGenericArrayParameter(String input, List<Integer>[] names) {
    return "";
  }

  @Test
  public void noContent() throws NoSuchMethodException {
    DataConverter dc = GlobalDataConverter.get();
    Method m = this.getClass().getMethod("testMethodGenericParameter", String.class, List.class);
    Object[] result =
        dc.fromPayloads(Optional.empty(), m.getParameterTypes(), m.getGenericParameterTypes());
    Assert.assertNull(result[0]);
    Assert.assertNull(result[1]);
  }

  @Test
  public void addParameter() throws NoSuchMethodException {
    DataConverter dc = GlobalDataConverter.get();
    Optional<Payloads> p = dc.toPayloads("test");
    Method m = this.getClass().getMethod("testMethodNormalParameter", String.class, String.class);
    Object[] result = dc.fromPayloads(p, m.getParameterTypes(), m.getGenericParameterTypes());
    Assert.assertEquals("test", result[0]);
    Assert.assertNull(result[1]);
  }

  @Test
  public void addGenericParameter() throws NoSuchMethodException {
    DataConverter dc = GlobalDataConverter.get();
    Optional<Payloads> p = dc.toPayloads("test");
    Method m = this.getClass().getMethod("testMethodGenericParameter", String.class, List.class);
    Object[] result = dc.fromPayloads(p, m.getParameterTypes(), m.getGenericParameterTypes());
    Assert.assertEquals("test", result[0]);
    Assert.assertNull(result[1]);
  }

  @Test
  public void addGenericArrayParameter() throws NoSuchMethodException {
    DataConverter dc = GlobalDataConverter.get();
    Optional<Payloads> p = dc.toPayloads("test");
    Method m =
        this.getClass().getMethod("testMethodGenericArrayParameter", String.class, List[].class);
    Object[] result = dc.fromPayloads(p, m.getParameterTypes(), m.getGenericParameterTypes());
    Assert.assertEquals("test", result[0]);
    Assert.assertNull(result[1]);
  }
}
