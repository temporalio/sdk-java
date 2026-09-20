package io.temporal.common.converter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import org.junit.Test;

public class GsonJsonPayloadConverterTest {

  @Test
  public void serializationUsesTypeHint() {
    GsonJsonPayloadConverter converter = new GsonJsonPayloadConverter();
    Payload payload = converter.toData(new Child(), Parent.class).get();
    String json = payload.getData().toStringUtf8();

    assertTrue(json.contains("parent"));
    assertFalse(json.contains("child"));
  }

  private static class Parent {
    private final String parent = "parent";
  }

  private static class Child extends Parent {
    private final String child = "child";
  }
}
