/*
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

package com.uber.cadence.converter;

import com.google.common.base.Defaults;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.apache.thrift.protocol.TJSONProtocol;

/**
 * Implements conversion through GSON JSON processor. To extend use {@link
 * #JsonDataConverter(Function)} constructor. Thrift structures are converted using {@link
 * TJSONProtocol}. When using thrift only one argument of a method is expected.
 *
 * @author fateev
 */
public final class JsonDataConverter implements DataConverter {

  private static final DataConverter INSTANCE = new JsonDataConverter();
  private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
  private static final String TYPE_FIELD_NAME = "type";
  private static final String JSON_CONVERTER_TYPE = "JSON";
  private static final String CLASS_NAME_FIELD_NAME = "className";
  private final Gson gson;

  public static DataConverter getInstance() {
    return INSTANCE;
  }

  private JsonDataConverter() {
    this((b) -> b);
  }

  /**
   * Constructs an instance giving an ability to override {@link Gson} initialization.
   *
   * @param builderInterceptor function that intercepts {@link GsonBuilder} construction.
   */
  public JsonDataConverter(Function<GsonBuilder, GsonBuilder> builderInterceptor) {
    GsonBuilder gsonBuilder =
        new GsonBuilder()
            .serializeNulls()
            .registerTypeAdapterFactory(new ThrowableTypeAdapterFactory())
            .registerTypeAdapterFactory(new TBaseTypeAdapterFactory())
            .registerTypeAdapterFactory(new TEnumTypeAdapterFactory());
    GsonBuilder intercepted = builderInterceptor.apply(gsonBuilder);
    gson = intercepted.create();
  }

  /**
   * When values is empty or it contains a single value and it is null then return empty blob. If a
   * single value do not wrap it into Json array. Exception stack traces are converted to a single
   * string stack trace to save space and make them more readable.
   */
  @Override
  public byte[] toData(Object... values) throws DataConverterException {
    if (values == null || values.length == 0) {
      return null;
    }
    try {
      if (values.length == 1) {
        Object value = values[0];
        String json = gson.toJson(value);
        return json.getBytes(StandardCharsets.UTF_8);
      }
      String json = gson.toJson(values);
      return json.getBytes(StandardCharsets.UTF_8);
    } catch (DataConverterException e) {
      throw e;
    } catch (Throwable e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromData(byte[] content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    if (content == null) {
      return null;
    }
    try {
      return gson.fromJson(new String(content, StandardCharsets.UTF_8), valueType);
    } catch (Exception e) {
      throw new DataConverterException(content, new Type[] {valueType}, e);
    }
  }

  @Override
  public Object[] fromDataArray(byte[] content, Type... valueTypes) throws DataConverterException {
    try {
      if (content == null) {
        if (valueTypes.length == 0) {
          return EMPTY_OBJECT_ARRAY;
        }
        throw new DataConverterException(
            "Content doesn't match expected arguments", content, valueTypes);
      }
      if (valueTypes.length == 1) {
        Object result = gson.fromJson(new String(content, StandardCharsets.UTF_8), valueTypes[0]);
        return new Object[] {result};
      }

      JsonElement element = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
      JsonArray array;
      if (element instanceof JsonArray) {
        array = element.getAsJsonArray();
      } else {
        array = new JsonArray();
        array.add(element);
      }

      Object[] result = new Object[valueTypes.length];
      for (int i = 0; i < valueTypes.length; i++) {

        if (i >= array.size()) { // Missing arugments => add defaults
          Type t = valueTypes[i];
          if (t instanceof Class) {
            result[i] = Defaults.defaultValue((Class<?>) t);
          } else {
            result[i] = null;
          }
        } else {
          result[i] = gson.fromJson(array.get(i), valueTypes[i]);
        }
      }
      return result;
    } catch (DataConverterException e) {
      throw e;
    } catch (Exception e) {
      throw new DataConverterException(content, valueTypes, e);
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
            out.name(TYPE_FIELD_NAME).value(JSON_CONVERTER_TYPE);
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
            if (!"JSON".equals(value)) {
              throw new IOException(
                  "Cannot deserialize DataConverter. Expected type is JSON. " + "Found " + value);
            }
            in.endObject();
            return (T) JsonDataConverter.getInstance();
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
