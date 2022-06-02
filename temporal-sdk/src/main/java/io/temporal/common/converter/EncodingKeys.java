/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
