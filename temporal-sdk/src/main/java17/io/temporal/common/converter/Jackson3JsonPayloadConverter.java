package io.temporal.common.converter;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link PayloadConverter} that uses Jackson 3.x for JSON serialization/deserialization. This
 * converter uses the same {@code "json/plain"} encoding type as {@link
 * JacksonJsonPayloadConverter}, making it wire-compatible.
 *
 * <p>Requires Java 17+ and {@code tools.jackson.core:jackson-databind:3.x} on the classpath.
 *
 * <p>Jackson 3.x has built-in support for {@code java.time} types and {@code java.util.Optional},
 * so no additional module registration is needed.
 *
 * @see JacksonJsonPayloadConverter#setDefaultAsJackson3(boolean, boolean)
 */
@Experimental
public class Jackson3JsonPayloadConverter implements PayloadConverter {

  private final JsonMapper mapper;

  /**
   * Creates a new instance with the default {@link JsonMapper} configuration using Jackson 3.x
   * native defaults. Equivalent to {@code new Jackson3JsonPayloadConverter(false)}.
   */
  public Jackson3JsonPayloadConverter() {
    this(false);
  }

  /**
   * Creates a new instance with the default {@link JsonMapper} configuration.
   *
   * <p>The defaults always include:
   *
   * <ul>
   *   <li>Dates are written as ISO-8601 strings, not timestamps
   *   <li>Timezone from the server payload is preserved without adjusting to the host timezone
   *   <li>All fields are visible for serialization regardless of access modifiers
   * </ul>
   *
   * @param jackson2Compat if {@code true}, uses {@link JsonMapper#builderWithJackson2Defaults()} to
   *     preserve Jackson 2.x default behaviors for maximum wire compatibility. If {@code false},
   *     uses Jackson 3.x native defaults.
   */
  public Jackson3JsonPayloadConverter(boolean jackson2Compat) {
    this(newDefaultJsonMapper(jackson2Compat));
  }

  /**
   * Creates a new instance with a custom {@link JsonMapper}.
   *
   * @param mapper a pre-configured Jackson 3.x {@link JsonMapper}
   */
  public Jackson3JsonPayloadConverter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Creates a default {@link JsonMapper} with configuration defaults matching {@link
   * JacksonJsonPayloadConverter#newDefaultObjectMapper()}.
   *
   * <p>Can be used as a starting point for custom user configurations:
   *
   * <pre>{@code
   * JsonMapper mapper = Jackson3JsonPayloadConverter.newDefaultJsonMapper(true)
   *     .rebuild()
   *     .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
   *     .build();
   * new Jackson3JsonPayloadConverter(mapper);
   * }</pre>
   *
   * @param jackson2Compat if {@code true}, uses {@link JsonMapper#builderWithJackson2Defaults()} to
   *     preserve Jackson 2.x default behaviors for maximum wire compatibility. If {@code false},
   *     uses Jackson 3.x native defaults.
   * @return a default configuration of {@link JsonMapper}
   */
  public static JsonMapper newDefaultJsonMapper(boolean jackson2Compat) {
    JsonMapper.Builder builder =
        jackson2Compat ? JsonMapper.builderWithJackson2Defaults() : JsonMapper.builder();
    return builder
        // preserve the original value of timezone coming from the server in Payload
        // without adjusting to the host timezone
        // may be important if the replay is happening on a host in another timezone
        .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .changeDefaultVisibility(
            vc -> vc.withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY))
        .build();
  }

  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_JSON_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    try {
      byte[] serialized = mapper.writeValueAsBytes(value);
      return Optional.of(
          Payload.newBuilder()
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_JSON)
              .setData(ByteString.copyFrom(serialized))
              .build());
    } catch (JacksonException e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    ByteString data = content.getData();
    if (data.isEmpty()) {
      return null;
    }
    try {
      JavaType reference = mapper.getTypeFactory().constructType(valueType);
      return mapper.readValue(content.getData().toByteArray(), reference);
    } catch (JacksonException e) {
      throw new DataConverterException(e);
    }
  }
}
