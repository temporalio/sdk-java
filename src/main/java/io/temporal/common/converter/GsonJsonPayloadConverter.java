/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.common.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ByteString;
import io.temporal.proto.common.Payload;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

/**
 * Implements conversion through GSON JSON processor. To extend use {@link
 * #GsonJsonPayloadConverter(Function)} constructor.
 *
 * @author fateev
 */
public final class GsonJsonPayloadConverter implements PayloadConverter {

  private static final PayloadConverter INSTANCE = new GsonJsonPayloadConverter();

  private final Gson gson;

  public static PayloadConverter getInstance() {
    return INSTANCE;
  }

  public GsonJsonPayloadConverter() {
    this((b) -> b);
  }

  /**
   * Constructs an instance giving an ability to override {@link Gson} initialization.
   *
   * @param builderInterceptor function that intercepts {@link GsonBuilder} construction.
   */
  public GsonJsonPayloadConverter(Function<GsonBuilder, GsonBuilder> builderInterceptor) {
    GsonBuilder gsonBuilder = new GsonBuilder().serializeNulls();
    GsonBuilder intercepted = builderInterceptor.apply(gsonBuilder);
    gson = intercepted.create();
  }

  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_JSON_NAME;
  }

  /**
   * Return empty if value is null. Exception stack traces are converted to a single string stack
   * trace to save space and make them more readable.
   */
  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    try {
      String json = gson.toJson(value);
      return Optional.of(
          Payload.newBuilder()
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_JSON)
              .setData(ByteString.copyFrom(json, StandardCharsets.UTF_8))
              .build());
    } catch (DataConverterException e) {
      throw e;
    } catch (Throwable e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    if (content == null) {
      return null;
    }
    ByteString data = content.getData();
    if (data.isEmpty()) {
      return null;
    }
    try {
      String json = data.toString(StandardCharsets.UTF_8);
      return gson.fromJson(json, valueType);
    } catch (Exception e) {
      throw new DataConverterException(content, new Type[] {valueType}, e);
    }
  }
}
