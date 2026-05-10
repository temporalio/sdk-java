package io.temporal.common.converter;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Optional;

public class JacksonJsonPayloadConverter implements PayloadConverter {

  private static volatile PayloadConverter jackson3Delegate;

  /**
   * Opts in to or out of using Jackson 3.x as the default JSON payload converter. When enabled,
   * instances created via the default constructor will delegate all serialization/deserialization
   * to a {@link Jackson3JsonPayloadConverter}.
   *
   * <p>This applies globally, including to the converter in {@link
   * DefaultDataConverter#STANDARD_PAYLOAD_CONVERTERS}. Call this method early in your application,
   * before creating any Temporal clients.
   *
   * <p>Requires Java 17+ and {@code tools.jackson.core:jackson-databind:3.x} on the classpath.
   *
   * @param defaultAsJackson3 {@code true} to delegate to Jackson 3, {@code false} to revert to
   *     Jackson 2
   * @param jackson2Compat if {@code true}, the Jackson 3 converter is configured with Jackson 2.x
   *     default behaviors for maximum wire compatibility. If {@code false}, Jackson 3.x native
   *     defaults are used. Only relevant when {@code defaultAsJackson3} is {@code true}.
   * @throws IllegalStateException if Jackson 3 is not available
   * @see Jackson3JsonPayloadConverter
   */
  @Experimental
  public static void setDefaultAsJackson3(boolean defaultAsJackson3, boolean jackson2Compat) {
    if (!defaultAsJackson3) {
      jackson3Delegate = null;
      return;
    }
    try {
      jackson3Delegate =
          (PayloadConverter)
              Class.forName("io.temporal.common.converter.Jackson3JsonPayloadConverter")
                  .getDeclaredConstructor(boolean.class)
                  .newInstance(jackson2Compat);
    } catch (Exception | LinkageError e) {
      throw new IllegalStateException(
          "Failed to load Jackson 3 converter. Ensure Java 17+ and"
              + " Jackson 3.x (tools.jackson.core:jackson-databind) are on the classpath.",
          e);
    }
  }

  private final ObjectMapper mapper;
  private final boolean useDefaultJackson3Delegate;

  /**
   * Can be used as a starting point for custom user configurations of ObjectMapper.
   *
   * @return a default configuration of {@link ObjectMapper} used by {@link
   *     JacksonJsonPayloadConverter}.
   */
  public static ObjectMapper newDefaultObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    // preserve the original value of timezone coming from the server in Payload
    // without adjusting to the host timezone
    // may be important if the replay is happening on a host in another timezone
    mapper.configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false);
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(new Jdk8Module());
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    return mapper;
  }

  public JacksonJsonPayloadConverter() {
    this.mapper = newDefaultObjectMapper();
    this.useDefaultJackson3Delegate = true;
  }

  public JacksonJsonPayloadConverter(ObjectMapper mapper) {
    this.mapper = mapper;
    this.useDefaultJackson3Delegate = false;
  }

  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_JSON_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    // Delegate to Jackson 3 converter if globally opted in via setDefaultAsJackson3
    PayloadConverter delegate = jackson3Delegate;
    if (delegate != null && useDefaultJackson3Delegate) {
      return delegate.toData(value);
    }

    try {
      byte[] serialized = mapper.writeValueAsBytes(value);
      return Optional.of(
          Payload.newBuilder()
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_JSON)
              .setData(ByteString.copyFrom(serialized))
              .build());

    } catch (JsonProcessingException e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    // Delegate to Jackson 3 converter if globally opted in via setDefaultAsJackson3
    PayloadConverter delegate = jackson3Delegate;
    if (delegate != null && useDefaultJackson3Delegate) {
      return delegate.fromData(content, valueClass, valueType);
    }

    ByteString data = content.getData();
    if (data.isEmpty()) {
      return null;
    }
    try {
      @SuppressWarnings("deprecation")
      JavaType reference = mapper.getTypeFactory().constructType(valueType, valueClass);
      return mapper.readValue(content.getData().toByteArray(), reference);
    } catch (IOException e) {
      throw new DataConverterException(e);
    }
  }
}
