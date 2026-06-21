package io.temporal.common.converter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.ResetWorkflowFailureInfo;
import io.temporal.api.failure.v1.TimeoutFailureInfo;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.HasWorkflowSerializationContext;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A delegating {@link DataConverter} implementation that wraps and chains both another {@link
 * DataConverter} and several {@link PayloadCodec}s.
 *
 * <p>The underlying {@link DataConverter} is expected to be responsible for conversion between user
 * objects and bytes represented as {@link Payloads}, while the underlying chain of codecs is
 * responsible for a subsequent byte &lt;-&gt; byte manipulation such as encryption or compression
 */
public class CodecDataConverter implements DataConverter, PayloadCodec {
  private static final String ENCODED_FAILURE_MESSAGE = "Encoded failure";

  /**
   * Metadata encoding value for claim check payloads stored in external storage. When a payload is
   * stored externally, the payload in Event History has this encoding and contains the serialized
   * claim data.
   */
  static final String EXTERNAL_STORAGE_ENCODING_NAME = "temporal-external-storage/claim";

  static final ByteString EXTERNAL_STORAGE_ENCODING =
      ByteString.copyFrom(EXTERNAL_STORAGE_ENCODING_NAME, StandardCharsets.UTF_8);

  /** Metadata key for the driver name in a claim payload. */
  static final String CLAIM_DRIVER_NAME_KEY = "driver-name";

  private final DataConverter dataConverter;
  private final ChainCodec chainCodec;
  private final boolean encodeFailureAttributes;
  private final @Nullable ExternalStorage externalStorage;
  private final @Nullable SerializationContext serializationContext;

  /**
   * When serializing to Payloads:
   *
   * <ul>
   *   <li>{@code dataConverter} is applied first, following by the chain of {@code codecs}.
   *   <li>{@code codecs} are applied last to first meaning the earlier encoders wrap the later ones
   * </ul>
   *
   * When deserializing from Payloads:
   *
   * <ul>
   *   <li>{@code codecs} are applied first to last to reverse the effect following by the {@code
   *       dataConverter}
   *   <li>{@code dataConverter} is applied last
   * </ul>
   *
   * See {@link #CodecDataConverter(DataConverter, Collection, boolean)} to enable encryption of
   * Failure attributes.
   *
   * @param dataConverter to delegate data conversion to
   * @param codecs to delegate bytes encoding/decoding to. When encoding, the codecs are applied
   *     last to first meaning the earlier encoders wrap the later ones. When decoding, the decoders
   *     are applied first to last to reverse the effect
   */
  public CodecDataConverter(DataConverter dataConverter, Collection<PayloadCodec> codecs) {
    this(dataConverter, codecs, false);
  }

  /**
   * When serializing to Payloads:
   *
   * <ul>
   *   <li>{@code dataConverter} is applied first, following by the chain of {@code codecs}.
   *   <li>{@code codecs} are applied last to first meaning the earlier encoders wrap the later ones
   * </ul>
   *
   * When deserializing from Payloads:
   *
   * <ul>
   *   <li>{@code codecs} are applied first to last to reverse the effect following by the {@code
   *       dataConverter}
   *   <li>{@code dataConverter} is applied last
   * </ul>
   *
   * Setting {@code encodeFailureAttributes} to true enables codec encoding of Failure attributes.
   * This can be used in conjunction with an encrypting codec to enable encryption of failures
   * message and stack traces. Note that failure's details are always codec-encoded, without regard
   * to {@code encodeFailureAttributes}.
   *
   * @param dataConverter to delegate data conversion to
   * @param codecs to delegate bytes encoding/decoding to. When encoding, the codecs are applied
   *     last to first meaning the earlier encoders wrap the later ones. When decoding, the decoders
   *     are applied first to last to reverse the effect
   * @param encodeFailureAttributes enable encoding of Failure attributes (message and stack trace)
   */
  public CodecDataConverter(
      DataConverter dataConverter,
      Collection<PayloadCodec> codecs,
      boolean encodeFailureAttributes) {
    this(dataConverter, new ChainCodec(codecs), encodeFailureAttributes, null, null);
  }

  /**
   * Creates a {@link CodecDataConverter} with external storage support.
   *
   * <p>When {@code externalStorage} is configured, payloads exceeding the configured size threshold
   * are offloaded to external storage after codec encoding. On deserialization, claim tokens are
   * detected and the original payloads are retrieved from external storage before codec decoding.
   *
   * @param dataConverter to delegate data conversion to
   * @param codecs to delegate bytes encoding/decoding to
   * @param encodeFailureAttributes enable encoding of Failure attributes
   * @param externalStorage optional external storage configuration
   */
  public CodecDataConverter(
      DataConverter dataConverter,
      Collection<PayloadCodec> codecs,
      boolean encodeFailureAttributes,
      @Nullable ExternalStorage externalStorage) {
    this(dataConverter, new ChainCodec(codecs), encodeFailureAttributes, externalStorage, null);
  }

  CodecDataConverter(
      DataConverter dataConverter,
      ChainCodec codecs,
      boolean encodeFailureAttributes,
      @Nullable ExternalStorage externalStorage,
      @Nullable SerializationContext serializationContext) {
    this.dataConverter = dataConverter;
    this.chainCodec = codecs;
    this.encodeFailureAttributes = encodeFailureAttributes;
    this.externalStorage = externalStorage;
    this.serializationContext = serializationContext;
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) {
    Optional<Payload> payload =
        ConverterUtils.withContext(dataConverter, serializationContext).toPayload(value);
    List<Payload> encodedPayloads =
        ConverterUtils.withContext(chainCodec, serializationContext)
            .encode(Collections.singletonList(payload.get()));
    Preconditions.checkState(encodedPayloads.size() == 1, "Expected one encoded payload");
    encodedPayloads = storeIfNeeded(encodedPayloads);
    return Optional.of(encodedPayloads.get(0));
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
    List<Payload> payloads = retrieveIfNeeded(Collections.singletonList(payload));
    List<Payload> decodedPayload =
        ConverterUtils.withContext(chainCodec, serializationContext).decode(payloads);
    Preconditions.checkState(decodedPayload.size() == 1, "Expected one decoded payload");
    return ConverterUtils.withContext(dataConverter, serializationContext)
        .fromPayload(decodedPayload.get(0), valueClass, valueType);
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
    Optional<Payloads> payloads =
        ConverterUtils.withContext(dataConverter, serializationContext).toPayloads(values);
    if (payloads.isPresent()) {
      List<Payload> encodedPayloads =
          ConverterUtils.withContext(chainCodec, serializationContext)
              .encode(payloads.get().getPayloadsList());
      encodedPayloads = storeIfNeeded(encodedPayloads);
      payloads = Optional.of(Payloads.newBuilder().addAllPayloads(encodedPayloads).build());
    }
    return payloads;
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException {
    if (content.isPresent()) {
      content = Optional.of(retrieveAndDecodePayloads(content.get()));
    }
    return ConverterUtils.withContext(dataConverter, serializationContext)
        .fromPayloads(index, content, valueType, valueGenericType);
  }

  @Override
  public Object[] fromPayloads(
      Optional<Payloads> content, Class<?>[] parameterTypes, Type[] genericParameterTypes)
      throws DataConverterException {
    if (content.isPresent()) {
      content = Optional.of(retrieveAndDecodePayloads(content.get()));
    }
    return ConverterUtils.withContext(dataConverter, serializationContext)
        .fromPayloads(content, parameterTypes, genericParameterTypes);
  }

  @Override
  @Nonnull
  public Failure exceptionToFailure(@Nonnull Throwable throwable) {
    Preconditions.checkNotNull(throwable, "throwable");
    return this.encodeFailure(
            ConverterUtils.withContext(dataConverter, serializationContext)
                .exceptionToFailure(throwable)
                .toBuilder())
        .build();
  }

  @Override
  @Nonnull
  public RuntimeException failureToException(@Nonnull Failure failure) {
    Preconditions.checkNotNull(failure, "failure");
    return ConverterUtils.withContext(dataConverter, serializationContext)
        .failureToException(this.decodeFailure(failure.toBuilder()).build());
  }

  @Nonnull
  @Override
  public CodecDataConverter withContext(@Nonnull SerializationContext context) {
    return new CodecDataConverter(
        dataConverter, chainCodec, encodeFailureAttributes, externalStorage, context);
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    return ConverterUtils.withContext(chainCodec, serializationContext).encode(payloads);
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    return ConverterUtils.withContext(chainCodec, serializationContext).decode(payloads);
  }

  private Failure.Builder encodeFailure(Failure.Builder failure) {
    if (failure.hasCause()) {
      failure.setCause(encodeFailure(failure.getCause().toBuilder()));
    }
    if (this.encodeFailureAttributes) {
      EncodedAttributes encodedAttributes = new EncodedAttributes();
      encodedAttributes.setStackTrace(failure.getStackTrace());
      encodedAttributes.setMessage(failure.getMessage());
      Payload encodedAttributesPayload = toPayload(Optional.of(encodedAttributes)).get();
      failure
          .setEncodedAttributes(encodedAttributesPayload)
          .setMessage(ENCODED_FAILURE_MESSAGE)
          .setStackTrace("");
    }
    switch (failure.getFailureInfoCase()) {
      case APPLICATION_FAILURE_INFO:
        {
          ApplicationFailureInfo.Builder info = failure.getApplicationFailureInfo().toBuilder();
          if (info.hasDetails()) {
            info.setDetails(encodePayloads(info.getDetails()));
          }
          failure.setApplicationFailureInfo(info);
        }
        break;
      case TIMEOUT_FAILURE_INFO:
        {
          TimeoutFailureInfo.Builder info = failure.getTimeoutFailureInfo().toBuilder();
          if (info.hasLastHeartbeatDetails()) {
            info.setLastHeartbeatDetails(encodePayloads(info.getLastHeartbeatDetails()));
          }
          failure.setTimeoutFailureInfo(info);
        }
        break;
      case CANCELED_FAILURE_INFO:
        {
          CanceledFailureInfo.Builder info = failure.getCanceledFailureInfo().toBuilder();
          if (info.hasDetails()) {
            info.setDetails(encodePayloads(info.getDetails()));
          }
          failure.setCanceledFailureInfo(info);
        }
        break;
      case RESET_WORKFLOW_FAILURE_INFO:
        {
          ResetWorkflowFailureInfo.Builder info = failure.getResetWorkflowFailureInfo().toBuilder();
          if (info.hasLastHeartbeatDetails()) {
            info.setLastHeartbeatDetails(encodePayloads(info.getLastHeartbeatDetails()));
          }
          failure.setResetWorkflowFailureInfo(info);
        }
        break;
      default:
        {
          // Other type of failure info don't have anything to encode
        }
    }
    return failure;
  }

  private Failure.Builder decodeFailure(Failure.Builder failure) {
    if (failure.hasCause()) {
      failure.setCause(decodeFailure(failure.getCause().toBuilder()));
    }
    if (failure.hasEncodedAttributes()) {
      EncodedAttributes encodedAttributes =
          fromPayload(
              failure.getEncodedAttributes(), EncodedAttributes.class, EncodedAttributes.class);
      failure
          .setStackTrace(encodedAttributes.getStackTrace())
          .setMessage(encodedAttributes.getMessage())
          .clearEncodedAttributes();
    }
    switch (failure.getFailureInfoCase()) {
      case APPLICATION_FAILURE_INFO:
        {
          ApplicationFailureInfo.Builder info = failure.getApplicationFailureInfo().toBuilder();
          if (info.hasDetails()) {
            info.setDetails(decodePayloads(info.getDetails()));
          }
          failure.setApplicationFailureInfo(info);
        }
        break;
      case TIMEOUT_FAILURE_INFO:
        {
          TimeoutFailureInfo.Builder info = failure.getTimeoutFailureInfo().toBuilder();
          if (info.hasLastHeartbeatDetails()) {
            info.setLastHeartbeatDetails(decodePayloads(info.getLastHeartbeatDetails()));
          }
          failure.setTimeoutFailureInfo(info);
        }
        break;
      case CANCELED_FAILURE_INFO:
        {
          CanceledFailureInfo.Builder info = failure.getCanceledFailureInfo().toBuilder();
          if (info.hasDetails()) {
            info.setDetails(decodePayloads(info.getDetails()));
          }
          failure.setCanceledFailureInfo(info);
        }
        break;
      case RESET_WORKFLOW_FAILURE_INFO:
        {
          ResetWorkflowFailureInfo.Builder info = failure.getResetWorkflowFailureInfo().toBuilder();
          if (info.hasLastHeartbeatDetails()) {
            info.setLastHeartbeatDetails(decodePayloads(info.getLastHeartbeatDetails()));
          }
          failure.setResetWorkflowFailureInfo(info);
        }
        break;
      default:
        {
          // Other type of failure info don't have anything to decode
        }
    }
    return failure;
  }

  private Payloads encodePayloads(Payloads decodedPayloads) {
    return Payloads.newBuilder().addAllPayloads(encode(decodedPayloads.getPayloadsList())).build();
  }

  private Payloads decodePayloads(Payloads encodedPayloads) {
    return Payloads.newBuilder().addAllPayloads(decode(encodedPayloads.getPayloadsList())).build();
  }

  /**
   * Retrieves externally stored payloads first, then decodes them through the codec chain. This
   * replaces the previous {@code decodePayloads} in the fromPayloads path.
   */
  private Payloads retrieveAndDecodePayloads(Payloads payloads) {
    List<Payload> retrieved = retrieveIfNeeded(payloads.getPayloadsList());
    List<Payload> decoded =
        ConverterUtils.withContext(chainCodec, serializationContext).decode(retrieved);
    return Payloads.newBuilder().addAllPayloads(decoded).build();
  }

  // ---- External Storage Helpers ----

  /**
   * Checks each encoded payload against the external storage threshold. Payloads exceeding the
   * threshold are uploaded to external storage and replaced with claim token payloads.
   */
  private List<Payload> storeIfNeeded(List<Payload> encodedPayloads) {
    if (externalStorage == null) {
      return encodedPayloads;
    }

    StorageDriverStoreContext storeContext = buildStoreContext();
    if (storeContext == null) {
      // No serialization context available; cannot construct store context.
      return encodedPayloads;
    }

    StorageDriver driver = externalStorage.selectDriver(storeContext);
    if (driver == null) {
      return encodedPayloads;
    }

    int threshold = externalStorage.getPayloadSizeThreshold();
    List<Payload> result = new ArrayList<>(encodedPayloads.size());
    for (Payload payload : encodedPayloads) {
      if (payload.getSerializedSize() >= threshold) {
        result.add(storePayload(driver, storeContext, payload));
      } else {
        result.add(payload);
      }
    }
    return result;
  }

  /** Stores a single payload externally and returns a claim token payload. */
  private Payload storePayload(
      StorageDriver driver, StorageDriverStoreContext storeContext, Payload payload) {
    byte[] payloadBytes = payload.toByteArray();
    List<StorageDriverClaim> claims =
        driver.store(storeContext, Collections.singletonList(payloadBytes));
    Preconditions.checkState(claims.size() == 1, "Expected one claim from store");
    return claimToPayload(driver.name(), claims.get(0));
  }

  /**
   * Checks each payload for claim tokens and retrieves the original payloads from external storage.
   */
  private List<Payload> retrieveIfNeeded(List<Payload> payloads) {
    if (externalStorage == null) {
      return payloads;
    }

    boolean hasClaims = false;
    for (Payload payload : payloads) {
      if (isClaimPayload(payload)) {
        hasClaims = true;
        break;
      }
    }
    if (!hasClaims) {
      return payloads;
    }

    StorageDriverRetrieveContext retrieveContext = buildRetrieveContext();
    List<Payload> result = new ArrayList<>(payloads.size());
    for (Payload payload : payloads) {
      if (isClaimPayload(payload)) {
        result.add(retrievePayload(retrieveContext, payload));
      } else {
        result.add(payload);
      }
    }
    return result;
  }

  /**
   * Retrieves a single payload from external storage using the claim information in the payload.
   */
  private Payload retrievePayload(
      StorageDriverRetrieveContext retrieveContext, Payload claimPayload) {
    String driverName = getClaimDriverName(claimPayload);
    StorageDriverClaim claim = payloadToClaim(claimPayload);
    StorageDriver driver = externalStorage.findDriverByName(driverName);
    List<byte[]> retrieved = driver.retrieve(retrieveContext, Collections.singletonList(claim));
    Preconditions.checkState(retrieved.size() == 1, "Expected one payload from retrieve");
    try {
      return Payload.parseFrom(retrieved.get(0));
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new StorageDriverException("Failed to parse retrieved payload", e);
    }
  }

  /** Checks if a payload is a claim token by inspecting its encoding metadata. */
  static boolean isClaimPayload(Payload payload) {
    ByteString encoding =
        payload.getMetadataOrDefault(EncodingKeys.METADATA_ENCODING_KEY, ByteString.EMPTY);
    return EXTERNAL_STORAGE_ENCODING.equals(encoding);
  }

  /**
   * Serializes a claim into a Payload with the external storage encoding. The claim data map is
   * stored as metadata entries and the payload body is empty.
   */
  static Payload claimToPayload(String driverName, StorageDriverClaim claim) {
    Payload.Builder builder = Payload.newBuilder();
    builder.putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EXTERNAL_STORAGE_ENCODING);
    builder.putMetadata(CLAIM_DRIVER_NAME_KEY, ByteString.copyFromUtf8(driverName));
    for (Map.Entry<String, String> entry : claim.getClaimData().entrySet()) {
      builder.putMetadata(entry.getKey(), ByteString.copyFromUtf8(entry.getValue()));
    }
    return builder.build();
  }

  /** Deserializes a claim from a claim token payload's metadata. */
  static StorageDriverClaim payloadToClaim(Payload claimPayload) {
    Map<String, String> claimData = new HashMap<>();
    for (Map.Entry<String, ByteString> entry : claimPayload.getMetadataMap().entrySet()) {
      String key = entry.getKey();
      // Skip the encoding and driver name metadata keys — they are not part of claim data.
      if (EncodingKeys.METADATA_ENCODING_KEY.equals(key) || CLAIM_DRIVER_NAME_KEY.equals(key)) {
        continue;
      }
      claimData.put(key, entry.getValue().toStringUtf8());
    }
    return new StorageDriverClaim(claimData);
  }

  /** Extracts the driver name from a claim token payload. */
  static String getClaimDriverName(Payload claimPayload) {
    ByteString driverNameBytes =
        claimPayload.getMetadataOrDefault(CLAIM_DRIVER_NAME_KEY, ByteString.EMPTY);
    if (driverNameBytes.isEmpty()) {
      throw new StorageDriverException(
          "Claim payload is missing '" + CLAIM_DRIVER_NAME_KEY + "' metadata");
    }
    return driverNameBytes.toStringUtf8();
  }

  @Nullable
  private StorageDriverStoreContext buildStoreContext() {
    if (serializationContext instanceof ActivitySerializationContext) {
      ActivitySerializationContext actCtx = (ActivitySerializationContext) serializationContext;
      return StorageDriverStoreContext.forActivity(
          actCtx.getNamespace(), actCtx.getWorkflowId(), null, actCtx.getActivityType());
    } else if (serializationContext instanceof HasWorkflowSerializationContext) {
      HasWorkflowSerializationContext wfCtx =
          (HasWorkflowSerializationContext) serializationContext;
      return StorageDriverStoreContext.forWorkflow(
          wfCtx.getNamespace(), wfCtx.getWorkflowId(), null);
    }
    // No context available — fall back to a default context.
    return StorageDriverStoreContext.forWorkflow("unknown", "unknown", null);
  }

  @Nonnull
  private StorageDriverRetrieveContext buildRetrieveContext() {
    if (serializationContext instanceof HasWorkflowSerializationContext) {
      HasWorkflowSerializationContext wfCtx =
          (HasWorkflowSerializationContext) serializationContext;
      return StorageDriverRetrieveContext.create(wfCtx.getNamespace(), wfCtx.getWorkflowId());
    }
    return StorageDriverRetrieveContext.create(null, null);
  }

  static class EncodedAttributes {
    private String message;

    private String stackTrace;

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    @JsonProperty("stack_trace")
    public String getStackTrace() {
      return stackTrace;
    }

    @JsonProperty("stack_trace")
    public void setStackTrace(String stackTrace) {
      this.stackTrace = stackTrace;
    }
  }
}
