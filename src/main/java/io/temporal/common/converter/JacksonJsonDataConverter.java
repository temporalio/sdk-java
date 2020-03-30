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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.google.common.base.Defaults;
import com.google.common.collect.ImmutableSet;
import io.temporal.internal.common.DataConverterUtils;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JacksonJsonDataConverter implements DataConverter {

  private static final Logger log = LoggerFactory.getLogger(CustomThrowableTypeAdapter.class);

  /** Used to parse a stack trace line. */
  private static final String TRACE_ELEMENT_REGEXP =
      "((?<className>.*)\\.(?<methodName>.*))\\(((?<fileName>.*?)(:(?<lineNumber>\\d+))?)\\)";

  private static final Pattern TRACE_ELEMENT_PATTERN = Pattern.compile(TRACE_ELEMENT_REGEXP);

  /**
   * Stop emitting stack trace after this line. Makes serialized stack traces more readable and
   * compact as it omits most of framework level code.
   */
  private static final ImmutableSet<String> CUTOFF_METHOD_NAMES =
      ImmutableSet.of(
          "io.temporal.internal.worker.POJOActivityImplementationFactory$POJOActivityImplementation.execute",
          "io.temporal.internal.sync.POJODecisionTaskHandler$POJOWorkflowImplementation.execute");

  private static final DataConverter INSTANCE = new JacksonJsonDataConverter();
  private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
  private static final String TYPE_FIELD_NAME = "type";
  private static final String JSON_CONVERTER_TYPE = "JSON";
  private final ObjectMapper mapper;

  public static DataConverter getInstance() {
    return INSTANCE;
  }

  private JacksonJsonDataConverter() {
    mapper = new ObjectMapper();
    SimpleModule module = new SimpleModule();
    module.addSerializer(new ThrowableSerializer());
    module.addSerializer(new DataConverterSerializer());
    module.addDeserializer(DataConverter.class, new DataConverterDeserializer(this));
    module.addDeserializer(Throwable.class, new ThrowableDeserializer());
    mapper.registerModule(module);
  }

  @Override
  public byte[] toData(Object... values) throws DataConverterException {
    try {
      if (values.length == 1) {
        return mapper.writeValueAsBytes(values[0]);
      }
      return mapper.writeValueAsBytes(values);
    } catch (JsonProcessingException e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromData(byte[] content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    try {
      @SuppressWarnings("deprecation")
      JavaType reference = mapper.getTypeFactory().constructType(valueType, valueClass);
      return mapper.readValue(content, reference);
    } catch (IOException e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public Object[] fromDataArray(byte[] content, Type... valueTypes) throws DataConverterException {
    if (content == null) {
      if (valueTypes.length == 0) {
        return EMPTY_OBJECT_ARRAY;
      }
      throw new DataConverterException(
          "Content doesn't match expected arguments", content, valueTypes);
    }
    try {
      if (valueTypes.length == 1) {
        JavaType reference = mapper.getTypeFactory().constructType(valueTypes[0]);
        Object result = mapper.readValue(content, reference);
        return new Object[] {result};
      }
      JsonParser parser = mapper.getFactory().createParser(content);
      TreeNode tree = mapper.readTree(parser);
      ArrayNode array;
      if (tree instanceof ArrayNode) {
        array = (ArrayNode) tree;
      } else {
        array = mapper.createArrayNode();
        array.add(tree.toString());
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
          JavaType toValueType = mapper.getTypeFactory().constructType(valueTypes[i]);
          JsonNode node = array.get(i);
          result[i] = mapper.convertValue(node, toValueType);
        }
      }
      return result;
    } catch (DataConverterException e) {
      throw e;
    } catch (Exception e) {
      throw new DataConverterException(content, valueTypes, e);
    }
  }

  private static class ThrowableSerializer extends StdSerializer<Throwable> {

    protected ThrowableSerializer() {
      super(Throwable.class);
    }

    @Override
    public void serialize(Throwable throwable, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      System.out.println("ThrowableSerializer: " + throwable.getClass().getName());
      // We want to serialize the throwable and its cause separately, so that if the throwable
      // is serializable but the cause is not, we can still serialize them correctly (i.e. we
      // serialize the throwable correctly and convert the cause to a data converter exception).
      // If existing cause is not detached due to security policy then null is returned.
      Throwable cause = DataConverterUtils.detachCause(throwable);

      ObjectNode object;
      //      try {
      JsonSerializer<Object> valueSerializer = provider.findValueSerializer(Object.class);
      valueSerializer.serialize(throwable, gen, provider);
      //        TypeAdapter exceptionTypeAdapter =
      //                gson.getDelegateAdapter(skipPast, TypeToken.get(throwable.getClass()));
      //        object = exceptionTypeAdapter.toJsonTree(throwable).getAsJsonObject();
      //        object.add("class", new JsonPrimitive(throwable.getClass().getName()));
      //        String stackTrace = DataConverterUtils.serializeStackTrace(throwable);
      //        object.add("stackTrace", new JsonPrimitive(stackTrace));
      //      } catch (Throwable e) {
      //        // In case a throwable is not serializable, we will convert it to a data converter
      // exception.
      //        // The cause of the data converter exception will indicate why the serialization
      // failed. On
      //        // the other hand, if the non-serializable throwable contains a cause, we will add
      // it to the
      //        // suppressed exceptions list.
      //        DataConverterException ee =
      //                new DataConverterException("Failure serializing exception: " +
      // throwable.toString(), e);
      //        if (cause != null) {
      //          ee.addSuppressed(cause);
      //          cause = null;
      //        }
      //
      //        TypeAdapter<Throwable> exceptionTypeAdapter =
      //                new CustomThrowableTypeAdapter<>(gson, skipPast);
      //        object = exceptionTypeAdapter.toJsonTree(ee).getAsJsonObject();
      //      }
      //
      //      if (cause != null) {
      //        TypeAdapter<Throwable> causeTypeAdapter = new CustomThrowableTypeAdapter<>(gson,
      // skipPast);
      //        try {
      //          object.add("cause", causeTypeAdapter.toJsonTree(cause));
      //        } catch (Throwable e) {
      //          DataConverterException ee =
      //                  new DataConverterException("Failure serializing exception: " +
      // cause.toString(), e);
      //          ee.setStackTrace(cause.getStackTrace());
      //          object.add("cause", causeTypeAdapter.toJsonTree(ee));
      //        }
      //      }
      //
      //      TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
      //      elementAdapter.write(jsonWriter, object);
    }
  }

  private static class ThrowableDeserializer extends StdDeserializer<Throwable> {

    protected ThrowableDeserializer() {
      super(Throwable.class);
    }

    @Override
    public Throwable deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException, JsonProcessingException {
      return null;
    }
  }

  private static class DataConverterSerializer extends StdSerializer<DataConverter> {

    protected DataConverterSerializer() {
      super(DataConverter.class);
    }

    @Override
    public void serialize(DataConverter value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      System.out.println("DataConverterSerializer: " + value.getClass().getName());
      gen.writeStartObject();
      gen.writeFieldName(TYPE_FIELD_NAME);
      gen.writeRawValue(JSON_CONVERTER_TYPE);
      gen.writeEndObject();
    }
  }

  private static class DataConverterDeserializer extends StdDeserializer<DataConverter> {

    private final DataConverter converter;

    protected DataConverterDeserializer(DataConverter converter) {
      super(DataConverter.class);
      this.converter = converter;
    }

    @Override
    public DataConverter deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException, JsonProcessingException {
      JsonNode tree = p.getCodec().readTree(p);
      JsonNode node = tree.get(TYPE_FIELD_NAME);
      if (node == null) {
        throw new IOException("Cannot deserialize DataConverter. Missing type field");
      }
      String value = node.textValue();
      if (!"JSON".equals(value)) {
        throw new IOException(
            "Cannot deserialize DataConverter. Expected type is JSON. " + "Found " + value);
      }
      return converter;
    }
  }
}
