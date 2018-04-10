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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements conversion through GSON JSON processor. To extend use {@link
 * JsonDataConverter(Function)} constructor.
 *
 * @author fateev
 */
public final class JsonDataConverter implements DataConverter {

  private static final Logger log = LoggerFactory.getLogger(JsonDataConverter.class);

  /** Used to parse a stack trace line. */
  private static final String TRACE_ELEMENT_REGEXP =
      "((?<className>.*)\\.(?<methodName>.*))\\(((?<fileName>.*?)(:(?<lineNumber>\\d+))?)\\)";

  private static final Pattern TRACE_ELEMENT_PATTERN = Pattern.compile(TRACE_ELEMENT_REGEXP);

  /**
   * Stop emitting stack trace after this line. Makes serialized stack traces more readable and
   * compact as it omits most of framework level code.
   */
  private static final Set<String> CUTOFF_METHOD_NAMES =
      new HashSet<String>() {
        {
          add(
              "com.uber.cadence.internal.worker.POJOActivityImplementationFactory$POJOActivityImplementation.execute");
          add(
              "com.uber.cadence.internal.sync.POJODecisionTaskHandler$POJOWorkflowImplementation.execute");
        }
      };

  private static final DataConverter INSTANCE = new JsonDataConverter();
  private static final byte[] EMPTY_BLOB = new byte[0];
  private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
  public static final String TYPE_FIELD_NAME = "type";
  public static final String JSON_CONVERTER_TYPE = "JSON";
  private final Gson gson;
  private final JsonParser parser = new JsonParser();

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
            .registerTypeAdapterFactory(new ThrowableTypeAdapterFactory());
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
      return EMPTY_BLOB;
    }
    try {
      if (values.length == 1) {
        String json = gson.toJson(values[0]);
        return json.getBytes(StandardCharsets.UTF_8);
      }
      String json = gson.toJson(values);
      return json.getBytes(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromData(byte[] content, Class<T> valueType) throws DataConverterException {
    if (content == null) {
      return null;
    }
    try {
      return gson.fromJson(new String(content, StandardCharsets.UTF_8), valueType);
    } catch (Exception e) {
      throw new DataConverterException(content, e);
    }
  }

  @Override
  public Object[] fromDataArray(byte[] content, Class<?>... valueType)
      throws DataConverterException {
    try {
      if ((content == null || content.length == 0)
          && (valueType == null || valueType.length == 0)) {
        return EMPTY_OBJECT_ARRAY;
      }
      if (valueType.length == 1) {
        return new Object[] {fromData(content, valueType[0])};
      }
      JsonArray array = parser.parse(new String(content, StandardCharsets.UTF_8)).getAsJsonArray();
      Object[] result = new Object[valueType.length];
      for (int i = 0; i < valueType.length; i++) {
        result[i] = gson.fromJson(array.get(i), valueType[i]);
      }
      return result;
    } catch (Exception e) {
      throw new DataConverterException(content, valueType, e);
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
            if (!in.nextName().equals("type")) {
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
      if (!Throwable.class.isAssignableFrom(typeToken.getRawType())) {
        return null; // this class only serializes 'Throwable' and its subtypes
      }

      final TypeAdapter<T> exceptionTypeAdapter = gson.getDelegateAdapter(this, typeToken);
      final TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
      TypeAdapter<T> result =
          new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter jsonWriter, T value) throws IOException {
              StringWriter sw = new StringWriter();
              PrintWriter pw = new PrintWriter(sw);
              Throwable throwable = (Throwable) value;
              StackTraceElement[] trace = throwable.getStackTrace();
              for (int i = 0; i < trace.length; i++) {
                StackTraceElement element = trace[i];
                pw.println(element);
                String fullMethodName = element.getClassName() + "." + element.getMethodName();
                if (CUTOFF_METHOD_NAMES.contains(fullMethodName)) {
                  break;
                }
              }
              JsonObject object = exceptionTypeAdapter.toJsonTree(value).getAsJsonObject();
              object.add("class", new JsonPrimitive(throwable.getClass().getName()));
              object.add("stackTrace", new JsonPrimitive(sw.toString()));
              elementAdapter.write(jsonWriter, object);
            }

            @Override
            public T read(JsonReader jsonReader) throws IOException {
              JsonObject object = elementAdapter.read(jsonReader).getAsJsonObject();
              JsonElement classElement = object.get("class");
              if (classElement != null) {
                String className = classElement.getAsString();
                Class<?> classType;
                try {
                  classType = Class.forName(className);
                } catch (ClassNotFoundException e) {
                  throw new IOException("Cannot deserialize " + className + " exception", e);
                }
                if (!Throwable.class.isAssignableFrom(classType)) {
                  throw new IOException("Expected type that extends Throwable: " + className);
                }
                final TypeAdapter<?> adapter =
                    gson.getDelegateAdapter(
                        ThrowableTypeAdapterFactory.this, TypeToken.get(classType));
                StackTraceElement[] stackTrace = parseStackTrace(object);
                // This is important. Initially I tried configuring ExclusionStrategy to not deserialize the stackTrace field.
                // But it left it null, which caused Thread.setStackTrace implementation to become silent noop.
                object.add("stackTrace", new JsonArray());
                Throwable result = (Throwable) adapter.fromJsonTree(object);
                result.setStackTrace(stackTrace);
                @SuppressWarnings("unchecked")
                T typedResult = (T) result;
                return typedResult;
              }
              return exceptionTypeAdapter.fromJsonTree(object);
            }
          }.nullSafe();
      return result;
    }

    private StackTraceElement[] parseStackTrace(JsonObject object) {
      JsonElement jsonStackTrace = object.get("stackTrace");
      if (jsonStackTrace == null) {
        return new StackTraceElement[0];
      }
      String stackTrace = jsonStackTrace.getAsString();
      if (stackTrace == null || stackTrace.isEmpty()) {
        return new StackTraceElement[0];
      }
      try {
        @SuppressWarnings("StringSplitter")
        String[] lines = stackTrace.split("\n");
        StackTraceElement[] result = new StackTraceElement[lines.length];
        for (int i = 0; i < lines.length; i++) {
          result[i] = parseStackTraceElement(lines[i]);
        }
        return result;
      } catch (Exception e) {
        log.warn("Failed to parse stack trace: " + stackTrace);
        return new StackTraceElement[0];
      }
    }
  }

  /**
   * See {@link StackTraceElement#toString()} for input specification.
   *
   * @param line line of stack trace.
   * @return StackTraceElement that contains data from that line.
   */
  private static StackTraceElement parseStackTraceElement(String line) {
    Matcher matcher = TRACE_ELEMENT_PATTERN.matcher(line);
    if (!matcher.matches()) {
      return null;
    }
    String declaringClass = matcher.group("className");
    String methodName = matcher.group("methodName");
    String fileName = matcher.group("fileName");
    int lineNumber = 0;
    String lns = matcher.group("lineNumber");
    if (lns != null && lns.length() > 0) {
      try {
        lineNumber = Integer.parseInt(matcher.group("lineNumber"));
      } catch (NumberFormatException e) {
      }
    }
    return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
  }
}
