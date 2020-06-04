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
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.google.protobuf.ByteString;
import io.temporal.proto.common.Payload;
import java.io.IOException;
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

  private static final String TYPE_FIELD_NAME = "type";
  private static final String CLASS_NAME_FIELD_NAME = "className";

  private final Gson gson;

  public static PayloadConverter getInstance() {
    return INSTANCE;
  }

  private GsonJsonPayloadConverter() {
    this((b) -> b);
  }

  /**
   * Constructs an instance giving an ability to override {@link Gson} initialization.
   *
   * @param builderInterceptor function that intercepts {@link GsonBuilder} construction.
   */
  public GsonJsonPayloadConverter(Function<GsonBuilder, GsonBuilder> builderInterceptor) {
    GsonBuilder gsonBuilder =
        new GsonBuilder()
            .serializeNulls()
            .registerTypeAdapterFactory(new ThrowableTypeAdapterFactory());
    GsonBuilder intercepted = builderInterceptor.apply(gsonBuilder);
    gson = intercepted.create();
  }

  /**
   * Return empty if value is null. Exception stack traces are converted to a single string stack
   * trace to save space and make them more readable.
   */
  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    if (value == null) {
      return Optional.empty();
    }
    try {
      if (value instanceof byte[]) {
        return Optional.of(
            Payload.newBuilder()
                .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_RAW)
                .setData(ByteString.copyFrom((byte[]) value))
                .build());
      }
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
      String encoding =
          content
              .getMetadataOrDefault(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_JSON)
              .toString(StandardCharsets.UTF_8);
      if (EncodingKeys.METADATA_ENCODING_JSON_NAME.equals(encoding)) {
        String json = data.toString(StandardCharsets.UTF_8);
        return gson.fromJson(json, valueType);
      }
      if (EncodingKeys.METADATA_ENCODING_RAW_NAME.equals(encoding)) {
        if (valueClass != byte[].class) {
          throw new IllegalArgumentException(
              "Raw encoding can be deserialized only to a byte array. valueClass="
                  + valueClass.getName());
        }
        return (T) data.toByteArray();
      }
      throw new IllegalArgumentException("Unknown encoding type: " + encoding);
    } catch (Exception e) {
      throw new DataConverterException(content, new Type[] {valueType}, e);
    }
  }

  /**
   * Special handling of exception serialization and deserialization. Default JSON for stack traces
   * is very space consuming and not readable by humans. So convert it into single text field and
   * then parse it back into StackTraceElement array.
   *
   * <p>Implementation idea is based on https://github.com/google/gson/issues/43
   */
  private static class ThrowableTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
      // Special handling of fields of DataConverter type.
      // Needed to serialize exceptions like ActivityTimeoutException.
      if (DataConverter.class.isAssignableFrom(typeToken.getRawType())) {
        return new TypeAdapter<T>() {
          @Override
          public void write(JsonWriter out, T value) throws IOException {
            out.beginObject();
            out.name(TYPE_FIELD_NAME).value(EncodingKeys.METADATA_ENCODING_JSON_NAME);
            out.endObject();
          }

          @Override
          @SuppressWarnings("unchecked")
          public T read(JsonReader in) throws IOException {
            in.beginObject();
            if (!in.nextName().equals(TYPE_FIELD_NAME)) {
              throw new IOException("Cannot deserialize DataConverter. Missing type field");
            }
            String value = in.nextString();
            if (!EncodingKeys.METADATA_ENCODING_JSON_NAME.equals(value)) {
              throw new IOException(
                  "Cannot deserialize DataConverter. Expected type is \""
                      + EncodingKeys.METADATA_ENCODING_JSON_NAME
                      + "\". Found "
                      + value);
            }
            in.endObject();
            return (T) GsonJsonDataConverter.getInstance();
          }
        };
      }
      if (Class.class.isAssignableFrom(typeToken.getRawType())) {
        return new TypeAdapter<T>() {
          @Override
          public void write(JsonWriter out, T value) throws IOException {
            out.beginObject();
            String className = ((Class) value).getName();
            out.name(CLASS_NAME_FIELD_NAME).value(className);
            out.endObject();
          }

          @Override
          public T read(JsonReader in) throws IOException {
            in.beginObject();
            if (!in.nextName().equals(CLASS_NAME_FIELD_NAME)) {
              throw new IOException(
                  "Cannot deserialize class. Missing " + CLASS_NAME_FIELD_NAME + " field");
            }
            String className = in.nextString();
            try {
              @SuppressWarnings("unchecked")
              T result = (T) Class.forName(className);
              in.endObject();
              return result;
            } catch (ClassNotFoundException e) {
              throw new RuntimeException(e);
            }
          }
        };
      }
      if (!Throwable.class.isAssignableFrom(typeToken.getRawType())) {
        return null; // this class only serializes 'Throwable' and its subtypes
      }

      return new CustomThrowableTypeAdapter(gson, this).nullSafe();
    }
  }
}
