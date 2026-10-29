package io.temporal.internal.nexus;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Test codec that stamps the serialization context it encodes under onto each payload, and records
 * the contexts it is asked to encode and decode under, so a test can tell which context was used
 * where.
 */
public final class ContextRecordingCodec implements PayloadCodec {
  public static final String CONTEXT_KEY = "test-serialization-context";

  // Shared by every instance derived via withContext, so a test sees every encode and decode.
  private final List<String> encodeContexts;
  private final List<String> decodeContexts;
  private final @Nullable SerializationContext context;

  public ContextRecordingCodec() {
    this(
        Collections.synchronizedList(new ArrayList<>()),
        Collections.synchronizedList(new ArrayList<>()),
        null);
  }

  private ContextRecordingCodec(
      List<String> encodeContexts,
      List<String> decodeContexts,
      @Nullable SerializationContext context) {
    this.encodeContexts = encodeContexts;
    this.decodeContexts = decodeContexts;
    this.context = context;
  }

  /** Forgets the contexts recorded so far. */
  public void reset() {
    encodeContexts.clear();
    decodeContexts.clear();
  }

  /** A data converter that runs every payload through this codec. */
  public DataConverter dataConverter() {
    return new CodecDataConverter(
        DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(this));
  }

  /** The contexts payloads were encoded under, in the form returned by {@link #describe}. */
  public List<String> encodeContexts() {
    synchronized (encodeContexts) {
      return new ArrayList<>(encodeContexts);
    }
  }

  /** The contexts payloads were decoded under, in the form returned by {@link #describe}. */
  public List<String> decodeContexts() {
    synchronized (decodeContexts) {
      return new ArrayList<>(decodeContexts);
    }
  }

  /** The context the payload was encoded under, or null if it was not encoded by this codec. */
  public static @Nullable String contextOf(Payload payload) {
    ByteString value = payload.getMetadataMap().get(CONTEXT_KEY);
    return value == null ? null : value.toStringUtf8();
  }

  /** A short, comparable description of a serialization context. */
  public static String describe(@Nullable SerializationContext context) {
    if (context instanceof NexusSerializationContext) {
      NexusSerializationContext nexus = (NexusSerializationContext) context;
      return "nexus:" + nexus.getEndpoint() + "/" + nexus.getService() + "/" + nexus.getOperation();
    }
    if (context instanceof WorkflowSerializationContext) {
      return "workflow:" + ((WorkflowSerializationContext) context).getWorkflowId();
    }
    if (context instanceof ActivitySerializationContext) {
      return "activity:" + ((ActivitySerializationContext) context).getActivityType();
    }
    return "none";
  }

  /** Returns a copy of {@code payload} stamped as if encoded under {@code context}. */
  public static Payload stamp(Payload payload, @Nullable SerializationContext context) {
    return Payload.newBuilder(payload)
        .putMetadata(CONTEXT_KEY, ByteString.copyFromUtf8(describe(context)))
        .build();
  }

  @Override
  @Nonnull
  public PayloadCodec withContext(@Nonnull SerializationContext context) {
    return new ContextRecordingCodec(encodeContexts, decodeContexts, context);
  }

  @Override
  @Nonnull
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    encodeContexts.add(describe(context));
    List<Payload> encoded = new ArrayList<>(payloads.size());
    for (Payload payload : payloads) {
      encoded.add(stamp(payload, context));
    }
    return encoded;
  }

  @Override
  @Nonnull
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    decodeContexts.add(describe(context));
    List<Payload> decoded = new ArrayList<>(payloads.size());
    for (Payload payload : payloads) {
      decoded.add(Payload.newBuilder(payload).removeMetadata(CONTEXT_KEY).build());
    }
    return decoded;
  }
}
