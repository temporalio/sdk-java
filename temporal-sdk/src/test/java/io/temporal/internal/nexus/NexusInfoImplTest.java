package io.temporal.internal.nexus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.concurrent.structured.CancelSource;
import io.temporal.nexus.NexusOperationInfo;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public class NexusInfoImplTest {
  @Test
  public void serializesMetadataWithoutAccessingCancellationFuture() {
    CancelSource<CancellationException> source = new CancelSource<>(CancellationException::new);
    CancellationToken<CancellationException> token = spy(source.token());
    NexusOperationInfo info =
        new NexusInfoImpl(
            "namespace", "queue", "endpoint", "service", "operation", "request-id", token);
    DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;

    Payload payload = converter.toPayload(info).get();
    Map<?, ?> metadata = converter.fromPayload(payload, Map.class, Map.class);

    verify(token, never()).getCancellationFuture();
    assertFalse(metadata.containsKey("cancellationToken"));
    assertEquals("namespace", metadata.get("namespace"));
    assertEquals("queue", metadata.get("taskQueue"));
    assertEquals("endpoint", metadata.get("endpoint"));
    assertEquals("service", metadata.get("service"));
    assertEquals("operation", metadata.get("operation"));
    assertEquals("request-id", metadata.get("requestId"));
    assertSame(token, info.getCancellationToken());
  }
}
