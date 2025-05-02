package io.temporal.common.converter;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;

public class EncodingKeys {
  public static final String METADATA_ENCODING_KEY = "encoding";
  public static final String METADATA_MESSAGE_TYPE_KEY = "messageType";

  static final String METADATA_ENCODING_NULL_NAME = "binary/null";
  static final ByteString METADATA_ENCODING_NULL =
      ByteString.copyFrom(METADATA_ENCODING_NULL_NAME, StandardCharsets.UTF_8);

  static final String METADATA_ENCODING_RAW_NAME = "binary/plain";
  static final ByteString METADATA_ENCODING_RAW =
      ByteString.copyFrom(METADATA_ENCODING_RAW_NAME, StandardCharsets.UTF_8);

  static final String METADATA_ENCODING_JSON_NAME = "json/plain";
  static final ByteString METADATA_ENCODING_JSON =
      ByteString.copyFrom(METADATA_ENCODING_JSON_NAME, StandardCharsets.UTF_8);

  static final String METADATA_ENCODING_PROTOBUF_JSON_NAME = "json/protobuf";
  static final ByteString METADATA_ENCODING_PROTOBUF_JSON =
      ByteString.copyFrom(METADATA_ENCODING_PROTOBUF_JSON_NAME, StandardCharsets.UTF_8);

  static final String METADATA_ENCODING_PROTOBUF_NAME = "binary/protobuf";
  static final ByteString METADATA_ENCODING_PROTOBUF =
      ByteString.copyFrom(METADATA_ENCODING_PROTOBUF_NAME, StandardCharsets.UTF_8);
}
