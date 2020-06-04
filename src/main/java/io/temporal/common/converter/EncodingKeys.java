package io.temporal.common.converter;

import com.google.protobuf.ByteString;

import java.nio.charset.StandardCharsets;

class EncodingKeys {
  static final String METADATA_ENCODING_KEY = "encoding";
  static final String METADATA_ENCODING_RAW_NAME = "raw";
  static final ByteString METADATA_ENCODING_RAW =
      ByteString.copyFrom(METADATA_ENCODING_RAW_NAME, StandardCharsets.UTF_8);
  static final String METADATA_ENCODING_JSON_NAME = "json";
  static final ByteString METADATA_ENCODING_JSON =
      ByteString.copyFrom(METADATA_ENCODING_JSON_NAME, StandardCharsets.UTF_8);
}
