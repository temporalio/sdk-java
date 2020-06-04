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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.impl.BeanAsArraySerializer;
import com.fasterxml.jackson.databind.ser.impl.ObjectIdWriter;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import io.temporal.internal.common.DataConverterUtils;
import io.temporal.proto.common.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class JacksonJsonPayloadConverter implements PayloadConverter {

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

  private static final PayloadConverter INSTANCE = new JacksonJsonPayloadConverter();
  private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
  private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];
  private static final String TYPE_FIELD_NAME = "type";
  private static final String JSON_CONVERTER_TYPE = "JSON";
  private final ObjectMapper mapper;

  public static PayloadConverter getInstance() {
    return INSTANCE;
  }

  private JacksonJsonPayloadConverter() {
    mapper = new ObjectMapper();
    SimpleModule module =
        new SimpleModule() {
          @Override
          public void setupModule(SetupContext context) {
            super.setupModule(context);
            //            addSerializer(new ThrowableSerializer((BeanSerializerBase) serializer));
            addSerializer(new DataConverterSerializer());
            //            addDeserializer(
            //                DataConverter.class,
            //                new DataConverterDeserializer(JacksonJsonPayloadConverter.this));
            addDeserializer(Throwable.class, new ThrowableDeserializer());
            context.addBeanSerializerModifier(
                new BeanSerializerModifier() {

                  public JsonSerializer<?> modifySerializer(
                      SerializationConfig config,
                      BeanDescription beanDesc,
                      JsonSerializer<?> serializer) {
                    if (serializer instanceof BeanSerializerBase) {
                      return new ThrowableSerializer((BeanSerializerBase) serializer);
                    }
                    return serializer;
                  }
                });
          }
        };
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    mapper.registerModule(module);
  }

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
    if (content == null) {
      return null;
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

  private static class ThrowableSerializer extends BeanSerializerBase {

    protected ThrowableSerializer(BeanSerializerBase serializer) {
      super(serializer);
    }

    public ThrowableSerializer(ThrowableSerializer src, ObjectIdWriter objectIdWriter) {
      super(src, objectIdWriter);
    }

    public ThrowableSerializer(ThrowableSerializer throwableSerializer, Set<String> toIgnore) {
      super(throwableSerializer, toIgnore);
    }

    @Override
    public BeanSerializerBase withObjectIdWriter(ObjectIdWriter objectIdWriter) {
      return new ThrowableSerializer(this, objectIdWriter);
    }

    @Override
    protected BeanSerializerBase withIgnorals(Set<String> toIgnore) {
      return new ThrowableSerializer(this, toIgnore);
    }

    @Override
    protected BeanSerializerBase asArraySerializer() {
      return new BeanAsArraySerializer(this);
    }

    @Override
    public BeanSerializerBase withFilterId(Object filterId) {
      throw new UnsupportedOperationException("unimplemented");
    }

    @Override
    public void serialize(Object bean, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      if (bean instanceof Throwable) {
        serializeThrowable((Throwable) bean, gen, provider);
        return;
      }
      gen.writeStartObject();
      serializeFields(bean, gen, provider);
      gen.writeEndObject();
    }

    public void serializeThrowable(
        Throwable throwable, JsonGenerator gen, SerializerProvider provider) throws IOException {
//      Throwable cause = DataConverterUtils.detachCause(throwable);
      String stackTrace = DataConverterUtils.serializeStackTrace(throwable);
      throwable.setStackTrace(EMPTY_STACK_TRACE);
      gen.writeStartObject();
      if (stackTrace != null) {
        gen.writeStringField("__stackTrace", stackTrace);
      }

      BeanPropertyWriter[] props = _props;
      int i = 0;
      try {
        for (final int len = props.length; i < len; ++i) {
          BeanPropertyWriter prop = props[i];
          if (prop == null) {
            continue;
          }
          String name = prop.getName();
          if (!name.equals("stackTrace") && !name.equals("localizedMessage")) {
            if (name.equals("cause") && throwable.getCause() == null) {
              continue;
            }
            if (name.equals("suppressed") && throwable.getSuppressed().length == 0) {
              continue;
            }
            prop.serializeAsField(throwable, gen, provider);
          }
        }
      } catch (Exception e) {
        String name = (i == props.length) ? "[anySetter]" : props[i].getName();
        wrapAndThrow(provider, e, throwable, name);
      } catch (StackOverflowError e) {
        JsonMappingException mapE =
            new JsonMappingException(gen, "Infinite recursion (StackOverflowError)", e);

        String name = (i == props.length) ? "[anySetter]" : props[i].getName();
        mapE.prependPath(new JsonMappingException.Reference(throwable, name));
        throw mapE;
      }
//      gen.writeStringField("class", throwable.getClass().getName());
//      if (cause != null) {
//        try {
//          gen.writeObjectField("cause", cause);
//        } catch (Throwable e) {
//          DataConverterException ee =
//              new DataConverterException("Failure serializing exception: " + cause.toString(), e);
//          ee.setStackTrace(cause.getStackTrace());
//          gen.writeObjectField("cause", ee);
//        }
//      }
      gen.writeEndObject();
    }

    //    @Override
    //    public void serialize(Throwable throwable, JsonGenerator gen, SerializerProvider provider)
    //        throws IOException {
    //      System.out.println("ThrowableSerializer: " + throwable.getClass().getName());
    //      // We want to serialize the throwable and its cause separately, so that if the throwable
    //      // is serializable but the cause is not, we can still serialize them correctly (i.e. we
    //      // serialize the throwable correctly and convert the cause to a data converter
    // exception).
    //      // If existing cause is not detached due to security policy then null is returned.
    //      Throwable cause = DataConverterUtils.detachCause(throwable);
    //
    //      ObjectNode object;
    //      //      try {
    //      JsonSerializer<Object> valueSerializer = provider.findValueSerializer(Object.class);
    //      valueSerializer.serialize(throwable, gen, provider);
    //      //        TypeAdapter exceptionTypeAdapter =
    //      //                gson.getDelegateAdapter(skipPast,
    // TypeToken.get(throwable.getClass()));
    //      //        object = exceptionTypeAdapter.toJsonTree(throwable).getAsJsonObject();
    //      //        object.add("class", new JsonPrimitive(throwable.getClass().getName()));
    //      //        String stackTrace = DataConverterUtils.serializeStackTrace(throwable);
    //      //        object.add("stackTrace", new JsonPrimitive(stackTrace));
    //      //      } catch (Throwable e) {
    //      //        // In case a throwable is not serializable, we will convert it to a data
    // converter
    //      // exception.
    //      //        // The cause of the data converter exception will indicate why the
    // serialization
    //      // failed. On
    //      //        // the other hand, if the non-serializable throwable contains a cause, we will
    // add
    //      // it to the
    //      //        // suppressed exceptions list.
    //      //        DataConverterException ee =
    //      //                new DataConverterException("Failure serializing exception: " +
    //      // throwable.toString(), e);
    //      //        if (cause != null) {
    //      //          ee.addSuppressed(cause);
    //      //          cause = null;
    //      //        }
    //      //
    //      //        TypeAdapter<Throwable> exceptionTypeAdapter =
    //      //                new CustomThrowableTypeAdapter<>(gson, skipPast);
    //      //        object = exceptionTypeAdapter.toJsonTree(ee).getAsJsonObject();
    //      //      }
    //      //
    //      //      if (cause != null) {
    //      //        TypeAdapter<Throwable> causeTypeAdapter = new
    // CustomThrowableTypeAdapter<>(gson,
    //      // skipPast);
    //      //        try {
    //      //          object.add("cause", causeTypeAdapter.toJsonTree(cause));
    //      //        } catch (Throwable e) {
    //      //          DataConverterException ee =
    //      //                  new DataConverterException("Failure serializing exception: " +
    //      // cause.toString(), e);
    //      //          ee.setStackTrace(cause.getStackTrace());
    //      //          object.add("cause", causeTypeAdapter.toJsonTree(ee));
    //      //        }
    //      //      }
    //      //
    //      //      TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
    //      //      elementAdapter.write(jsonWriter, object);
    //    }
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
