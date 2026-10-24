package io.temporal.payload.codec;

import io.temporal.api.common.v1.Payload;
import io.temporal.payload.context.SerializationContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Performs encoding/decoding on the payloads via the given codecs. When encoding, the codecs are
 * applied last to first meaning the earlier encoders wrap the later ones. When decoding, the codecs
 * are applied first to last to reverse the effect.
 */
public class ChainCodec implements PayloadCodec {
  private final List<PayloadCodec> codecs;
  private final @Nullable SerializationContext context;

  /**
   * @param codecs to apply. When encoding, the {@code codecs} are applied last to first meaning the
   *     earlier encoders wrap the later ones. When decoding, the {@code codecs} are applied first
   *     to last to reverse the effect
   */
  public ChainCodec(Collection<PayloadCodec> codecs) {
    this(codecs, null);
  }

  ChainCodec(Collection<PayloadCodec> codecs, @Nullable SerializationContext context) {
    this.codecs = new ArrayList<>(codecs);
    this.context = context;
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    ListIterator<PayloadCodec> iterator = codecs.listIterator(codecs.size());
    while (iterator.hasPrevious()) {
      PayloadCodec codec = iterator.previous();
      payloads = (context != null ? codec.withContext(context) : codec).encode(payloads);
    }
    return payloads;
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    for (PayloadCodec codec : codecs) {
      payloads = (context != null ? codec.withContext(context) : codec).decode(payloads);
    }
    return payloads;
  }

  @Override
  @Nonnull
  public PayloadCodec withContext(@Nonnull SerializationContext context) {
    return new ChainCodec(codecs, context);
  }

  /** Returns true when this chain contains no codecs other than empty chains. */
  public boolean isEmpty() {
    return codecs.stream()
        .allMatch(codec -> codec instanceof ChainCodec && ((ChainCodec) codec).isEmpty());
  }

  /**
   * Returns a header-only view that rejects missing encoders after contextual binding.
   *
   * <p>Binding before processing headers prevents a component's contextual codec from silently
   * turning an enabled header pipeline into an empty chain. Normal value codec behavior is
   * unchanged.
   */
  public PayloadCodec forHeaders() {
    List<PayloadCodec> bound = new ArrayList<>();
    collectHeaderCodecs(bound);
    ChainCodec execution = new ChainCodec(bound);
    return new PayloadCodec() {
      @Nonnull
      @Override
      public List<Payload> encode(@Nonnull List<Payload> payloads) {
        return execution.encode(payloads);
      }

      @Nonnull
      @Override
      public List<Payload> decode(@Nonnull List<Payload> payloads) {
        return execution.decode(payloads);
      }

      @Nonnull
      @Override
      public PayloadCodec withContext(@Nonnull SerializationContext context) {
        return new ChainCodec(codecs, context).forHeaders();
      }
    };
  }

  private void collectHeaderCodecs(List<PayloadCodec> bound) {
    if (codecs.isEmpty()) {
      throw new IllegalArgumentException("Header codec binding must not return an empty chain");
    }
    for (PayloadCodec codec : codecs) {
      PayloadCodec scoped =
          codec == null ? null : context == null ? codec : codec.withContext(context);
      if (scoped == null) {
        throw new IllegalArgumentException("Header codec binding must not return null");
      }
      if (scoped instanceof ChainCodec) {
        ((ChainCodec) scoped).collectHeaderCodecs(bound);
      } else {
        bound.add(scoped);
      }
    }
  }
}
