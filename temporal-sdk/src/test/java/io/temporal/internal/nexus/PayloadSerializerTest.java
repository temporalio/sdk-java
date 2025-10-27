package io.temporal.internal.nexus;

import com.google.common.reflect.TypeToken;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.EncodedValuesTest;
import java.util.Collections;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class PayloadSerializerTest {
  static DataConverter dataConverter = DefaultDataConverter.STANDARD_INSTANCE;
  PayloadSerializer payloadSerializer = new PayloadSerializer(dataConverter);

  @Test
  public void testPayload() {
    String original = "test";
    PayloadSerializer.Content content = payloadSerializer.serialize(original);
    Assert.assertEquals(original, payloadSerializer.deserialize(content, String.class));
  }

  @Test
  public void testNull() {
    PayloadSerializer.Content content = payloadSerializer.serialize(null);
    Assert.assertNull(payloadSerializer.deserialize(content, String.class));
  }

  @Test
  public void testInteger() {
    PayloadSerializer.Content content = payloadSerializer.serialize(1);
    Assert.assertEquals(1, payloadSerializer.deserialize(content, Integer.class));
  }

  @Test
  public void testArray() {
    String[] cars = {"test", "nexus", "serialization"};
    PayloadSerializer.Content content = payloadSerializer.serialize(cars);
    Assert.assertArrayEquals(
        cars, (String[]) payloadSerializer.deserialize(content, String[].class));
  }

  @Test
  public void testHashMap() {
    Map<String, EncodedValuesTest.Pair> map =
        Collections.singletonMap("key", new EncodedValuesTest.Pair(1, "hello"));
    PayloadSerializer.Content content = payloadSerializer.serialize(map);
    Map<String, EncodedValuesTest.Pair> newMap =
        (Map<String, EncodedValuesTest.Pair>)
            payloadSerializer.deserialize(
                content, (new TypeToken<Map<String, EncodedValuesTest.Pair>>() {}).getType());
    Assert.assertTrue(newMap.get("key") instanceof EncodedValuesTest.Pair);
  }

  @Test
  public void testProto() {
    WorkflowExecution exec =
        WorkflowExecution.newBuilder().setWorkflowId("id").setRunId("runId").build();
    PayloadSerializer.Content content = payloadSerializer.serialize(exec);
    Assert.assertEquals(exec, payloadSerializer.deserialize(content, WorkflowExecution.class));
  }
}
