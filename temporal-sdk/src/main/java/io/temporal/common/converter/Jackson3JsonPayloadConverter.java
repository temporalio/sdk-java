package io.temporal.common.converter;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * A {@link PayloadConverter} that uses Jackson 3.x for JSON serialization/deserialization. This
 * converter uses the same {@code "json/plain"} encoding type as {@link
 * JacksonJsonPayloadConverter}, making it wire-compatible.
 *
 * <p>This is a stub for Java versions prior to 17. On Java 17+ with Jackson 3.x on the classpath,
 * the real implementation is loaded automatically via the multi-release JAR mechanism.
 *
 * <p>Requires Java 17+ and {@code tools.jackson.core:jackson-databind:3.x} on the classpath.
 *
 * @see JacksonJsonPayloadConverter#setDefaultAsJackson3(boolean, boolean)
 */
@Experimental
public class Jackson3JsonPayloadConverter implements PayloadConverter {

  private static final String UNSUPPORTED_MSG =
      "Jackson 3 PayloadConverter requires Java 17+ and Jackson 3.x"
          + " (tools.jackson.core:jackson-databind) on the classpath";

  public Jackson3JsonPayloadConverter() {
    throw new UnsupportedOperationException(UNSUPPORTED_MSG);
  }

  public Jackson3JsonPayloadConverter(boolean jackson2Compat) {
    throw new UnsupportedOperationException(UNSUPPORTED_MSG);
  }

  @Override
  public String getEncodingType() {
    throw new UnsupportedOperationException(UNSUPPORTED_MSG);
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    throw new UnsupportedOperationException(UNSUPPORTED_MSG);
  }

  @Override
  public <T> T fromData(Payload content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException {
    throw new UnsupportedOperationException(UNSUPPORTED_MSG);
  }
}
