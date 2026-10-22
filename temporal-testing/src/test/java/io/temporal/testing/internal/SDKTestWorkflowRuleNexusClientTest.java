package io.temporal.testing.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.SimplePlugin;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.payload.context.SerializationContext;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class SDKTestWorkflowRuleNexusClientTest {
  private final RuntimeException expected =
      new IllegalStateException("configured converter was used");
  private final DataConverter configuredConverter = new RejectingConverter();

  @Test
  public void nexusClientUsesConfiguredWorkflowClientConverter() {
    SDKTestWorkflowRule testWorkflowRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setDataConverter(configuredConverter).build())
            .build();
    try {
      RuntimeException thrown =
          assertThrows(
              RuntimeException.class,
              () ->
                  testWorkflowRule
                      .getNexusClient()
                      .newUntypedNexusServiceClient("endpoint", "service")
                      .start(
                          "operation",
                          StartNexusOperationOptions.newBuilder().setId("id").build(),
                          "probe"));

      assertSame(expected, thrown);
    } finally {
      testWorkflowRule.getTestEnvironment().close();
    }
  }

  @Test
  public void nexusClientCustomizesConverterOnce() {
    AtomicBoolean customizedTwice = new AtomicBoolean();
    SimplePlugin plugin =
        SimplePlugin.newBuilder("customize-converter")
            .customizeDataConverter(
                converter -> {
                  if (converter instanceof CustomizedConverter) {
                    customizedTwice.set(true);
                  }
                  return new CustomizedConverter();
                })
            .build();
    SDKTestWorkflowRule testWorkflowRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowServiceStubsOptions(
                WorkflowServiceStubsOptions.newBuilder().setPlugins(plugin).build())
            .build();
    try {
      testWorkflowRule.getNexusClient();

      assertFalse(customizedTwice.get());
    } finally {
      testWorkflowRule.getTestEnvironment().close();
    }
  }

  private static final class CustomizedConverter extends DefaultDataConverter {
    private CustomizedConverter() {
      super(STANDARD_PAYLOAD_CONVERTERS);
    }
  }

  private final class RejectingConverter extends DefaultDataConverter {
    private RejectingConverter() {
      super(STANDARD_PAYLOAD_CONVERTERS);
    }

    @Override
    public DataConverter withContext(SerializationContext context) {
      return this;
    }

    @Override
    public <T> Optional<Payload> toPayload(T value) {
      if ("probe".equals(value)) {
        throw expected;
      }
      return super.toPayload(value);
    }
  }
}
