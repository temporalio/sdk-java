package io.temporal.workflow.contextPropagatonTests;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import java.util.Collections;
import java.util.Map;
import org.slf4j.MDC;

class TestContextPropagator implements ContextPropagator {

  public static final String DEFAULT_KEY = "test";

  private final String key;

  public TestContextPropagator() {
    this(DEFAULT_KEY);
  }

  public TestContextPropagator(String key) {
    this.key = key;
  }

  @Override
  public String getName() {
    return this.getClass().getName();
  }

  @Override
  public Map<String, Payload> serializeContext(Object context) {
    String testKey = (String) context;
    if (testKey != null) {
      return Collections.singletonMap(
          key, DataConverter.getDefaultInstance().toPayload(testKey).get());
    } else {
      return Collections.emptyMap();
    }
  }

  @Override
  public Object deserializeContext(Map<String, Payload> context) {
    if (context.containsKey(key)) {
      return DataConverter.getDefaultInstance()
          .fromPayload(context.get(key), String.class, String.class);

    } else {
      return null;
    }
  }

  @Override
  public Object getCurrentContext() {
    System.out.println("read:" + key);
    return MDC.get(key);
  }

  @Override
  public void setCurrentContext(Object context) {
    System.out.println("write:" + key + "value:" + context);
    MDC.put(key, String.valueOf(context));
  }
}
