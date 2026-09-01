package io.temporal.internal.common.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.reflect.TypeToken;
import com.google.protobuf.StringValue;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.RawValue;
import io.temporal.common.converter.TemporalTransferTypeConverter;
import io.temporal.common.converter.TransferTypeConverter;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class TemporalTransferTypeDataConverterTest {
  private DataConverter converter;

  @Before
  public void setUp() {
    converter = TemporalTransferTypeDataConverter.wrap(DefaultDataConverter.newDefaultInstance());
  }

  @Test
  public void protobufTransferTypeRoundTripsWithoutTransferMetadata() {
    Payload payload = converter.toPayload(new Model("value")).get();

    assertEquals("json/protobuf", payload.getMetadataOrThrow("encoding").toStringUtf8());
    assertFalse(payload.getMetadataMap().containsKey("temporal-transfer-type"));
    assertEquals(new Model("value"), converter.fromPayload(payload, Model.class, Model.class));
  }

  @Test
  public void wrapperIsIdempotentAndReusesConverterInstances() {
    assertSame(converter, TemporalTransferTypeDataConverter.wrap(converter));

    converter.toPayload(new ReuseModel("one"));
    converter.toPayload(new ReuseModel("two"));
    assertEquals(1, ReuseModelConverter.instances.get());
  }

  @Test
  public void nullRawAndUnannotatedValuesPassThrough() {
    assertEquals(
        DefaultDataConverter.STANDARD_PAYLOAD_CONVERTERS[0].getEncodingType(),
        converter.toPayload(null).get().getMetadataOrThrow("encoding").toStringUtf8());

    Payload rawPayload = DefaultDataConverter.newDefaultInstance().toPayload("raw").get();
    assertEquals(rawPayload, converter.toPayload(new RawValue(rawPayload)).get());
    assertEquals(
        rawPayload, converter.fromPayload(rawPayload, RawValue.class, RawValue.class).getPayload());
    assertEquals(
        "plain",
        converter.fromPayload(converter.toPayload("plain").get(), String.class, String.class));
    Payload nullPayload = DefaultDataConverter.newDefaultInstance().toPayload(null).get();
    assertNull(converter.fromPayload(nullPayload, FailingModel.class, FailingModel.class));
  }

  @Test
  public void mixedBatchesPreserveOrderAndMissingValues() {
    Optional<Payloads> payloads = converter.toPayloads(new Model("one"), "two", new Model("three"));
    Object[] values =
        converter.fromPayloads(
            payloads,
            new Class<?>[] {Model.class, String.class, Model.class, Model.class},
            new Type[] {Model.class, String.class, Model.class, Model.class});

    assertEquals(new Model("one"), values[0]);
    assertEquals("two", values[1]);
    assertEquals(new Model("three"), values[2]);
    assertNull(values[3]);
    assertNull(converter.fromPayloads(4, payloads, Model.class, Model.class));
  }

  @Test
  public void annotationIsExactAndDerivedClassMayOwnItsConverter() {
    Payload inherited = converter.toPayload(new DerivedWithoutAnnotation("value")).get();
    assertEquals("json/plain", inherited.getMetadataOrThrow("encoding").toStringUtf8());

    Payload own = converter.toPayload(new DerivedWithAnnotation("value")).get();
    assertEquals("json/protobuf", own.getMetadataOrThrow("encoding").toStringUtf8());
    assertEquals(
        new DerivedWithAnnotation("value"),
        converter.fromPayload(own, DerivedWithAnnotation.class, DerivedWithAnnotation.class));
  }

  @Test
  public void genericRequestedTypeIsPreservedAndSelectsTransferType() {
    Type stringType = new TypeToken<GenericValue<String>>() {}.getType();
    Type integerType = new TypeToken<GenericValue<Integer>>() {}.getType();

    Payload stringPayload = converter.toPayload(new GenericValue<String>("text")).get();
    Payload integerPayload = converter.toPayload(new GenericValue<Integer>(12)).get();
    GenericValue<String> stringValue =
        converter.fromPayload(stringPayload, GenericValue.class, stringType);
    GenericValue<Integer> integerValue =
        converter.fromPayload(integerPayload, GenericValue.class, integerType);

    assertEquals("text", stringValue.value);
    assertEquals(Integer.valueOf(12), integerValue.value);
    assertSame(integerType, GenericValueConverter.lastRequestedType);
  }

  @Test
  public void parameterizedTransferTypeIsPassedIntact() {
    Type modelType = new TypeToken<ListModel<String>>() {}.getType();
    ListModel<String> value =
        converter.fromPayload(
            converter.toPayload(new ListModel<String>(Arrays.asList("one", "two"))).get(),
            ListModel.class,
            modelType);

    assertEquals("one", value.values.get(0));
    assertEquals("two", value.values.get(1));
    assertTrue(ListModelConverter.lastTransferType instanceof ParameterizedType);
  }

  @Test
  public void invalidDeclarationsFailAsDataConverterExceptions() {
    assertInvalid(AbstractModel.class);
    assertInvalid(MissingPublicConstructorModel.class);
    assertInvalid(ThrowingConstructorModel.class);

    Payload payload = DefaultDataConverter.newDefaultInstance().toPayload("value").get();
    assertThrows(
        DataConverterException.class,
        () -> converter.fromPayload(payload, NullTransferModel.class, NullTransferModel.class));
  }

  @Test
  public void callbackFailuresPropagateUnchanged() {
    CallbackException expected =
        assertThrows(CallbackException.class, () -> converter.toPayload(new FailingModel()));
    assertEquals("callback", expected.getMessage());
  }

  @Test
  public void configuredConverterRemainsAuthoritativeAndReceivesContext() {
    DataConverter delegate = mock(DataConverter.class);
    DataConverter contextualDelegate = mock(DataConverter.class);
    SerializationContext context = mock(SerializationContext.class);
    Payload payload = Payload.getDefaultInstance();
    when(delegate.withContext(context)).thenReturn(contextualDelegate);
    when(contextualDelegate.toPayload(any())).thenReturn(Optional.of(payload));

    DataConverter contextual =
        TemporalTransferTypeDataConverter.wrap(delegate).withContext(context);
    assertSame(payload, contextual.toPayload(new Model("value")).get());

    ArgumentCaptor<Object> transferred = ArgumentCaptor.forClass(Object.class);
    verify(contextualDelegate).toPayload(transferred.capture());
    assertEquals(StringValue.of("value"), transferred.getValue());
    verify(delegate, never()).toPayload(any());
  }

  @Test
  public void failureMethodsForwardDirectly() {
    DataConverter delegate = mock(DataConverter.class);
    Failure failure = Failure.newBuilder().setMessage("failure").build();
    RuntimeException exception = new RuntimeException("exception");
    when(delegate.exceptionToFailure(exception)).thenReturn(failure);
    when(delegate.failureToException(failure)).thenReturn(exception);
    DataConverter wrapped = TemporalTransferTypeDataConverter.wrap(delegate);

    assertSame(failure, wrapped.exceptionToFailure(exception));
    assertSame(exception, wrapped.failureToException(failure));
    verify(delegate).exceptionToFailure(exception);
    verify(delegate).failureToException(failure);
  }

  private void assertInvalid(Class<?> modelClass) {
    DataConverterException exception =
        assertThrows(
            DataConverterException.class,
            () -> converter.toPayload(modelClass.getDeclaredConstructor().newInstance()));
    assertTrue(exception.getMessage().contains(modelClass.getName()));
  }

  @TemporalTransferTypeConverter(ModelConverter.class)
  public static class Model {
    public final String value;

    public Model(String value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Model && value.equals(((Model) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }

  public static final class ModelConverter implements TransferTypeConverter<Model> {
    public ModelConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return StringValue.class;
    }

    @Override
    public Object toTransferType(Model value) {
      return StringValue.of(value.value);
    }

    @Override
    public Model fromTransferType(Object value, Type valueType) {
      return new Model(((StringValue) value).getValue());
    }
  }

  @TemporalTransferTypeConverter(ReuseModelConverter.class)
  public static final class ReuseModel extends Model {
    public ReuseModel(String value) {
      super(value);
    }
  }

  public static final class ReuseModelConverter implements TransferTypeConverter<ReuseModel> {
    static final AtomicInteger instances = new AtomicInteger();

    public ReuseModelConverter() {
      instances.incrementAndGet();
    }

    @Override
    public Type getTransferType(Type valueType) {
      return StringValue.class;
    }

    @Override
    public Object toTransferType(ReuseModel value) {
      return StringValue.of(value.value);
    }

    @Override
    public ReuseModel fromTransferType(Object value, Type valueType) {
      return new ReuseModel(((StringValue) value).getValue());
    }
  }

  public static class DerivedWithoutAnnotation extends Model {
    public DerivedWithoutAnnotation(String value) {
      super(value);
    }
  }

  @TemporalTransferTypeConverter(DerivedConverter.class)
  public static class DerivedWithAnnotation extends Model {
    public DerivedWithAnnotation(String value) {
      super(value);
    }
  }

  public static final class DerivedConverter
      implements TransferTypeConverter<DerivedWithAnnotation> {
    public DerivedConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return StringValue.class;
    }

    @Override
    public Object toTransferType(DerivedWithAnnotation value) {
      return StringValue.of(value.value);
    }

    @Override
    public DerivedWithAnnotation fromTransferType(Object value, Type valueType) {
      return new DerivedWithAnnotation(((StringValue) value).getValue());
    }
  }

  @TemporalTransferTypeConverter(GenericValueConverter.class)
  public static final class GenericValue<T> {
    final T value;

    GenericValue(T value) {
      this.value = value;
    }
  }

  public static final class GenericValueConverter
      implements TransferTypeConverter<GenericValue<?>> {
    static Type lastRequestedType;

    public GenericValueConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      lastRequestedType = valueType;
      return ((ParameterizedType) valueType).getActualTypeArguments()[0];
    }

    @Override
    public Object toTransferType(GenericValue<?> value) {
      return value.value;
    }

    @Override
    public GenericValue<?> fromTransferType(Object value, Type valueType) {
      lastRequestedType = valueType;
      return new GenericValue<Object>(value);
    }
  }

  @TemporalTransferTypeConverter(ListModelConverter.class)
  public static final class ListModel<T> {
    final List<T> values;

    ListModel(List<T> values) {
      this.values = values;
    }
  }

  public static final class ListModelConverter implements TransferTypeConverter<ListModel<?>> {
    static Type lastTransferType;

    public ListModelConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      lastTransferType = new TypeToken<List<String>>() {}.getType();
      return lastTransferType;
    }

    @Override
    public Object toTransferType(ListModel<?> value) {
      return value.values;
    }

    @Override
    public ListModel<?> fromTransferType(Object value, Type valueType) {
      return new ListModel<Object>((List<Object>) value);
    }
  }

  @TemporalTransferTypeConverter(AbstractConverter.class)
  public static final class AbstractModel {
    public AbstractModel() {}
  }

  public abstract static class AbstractConverter implements TransferTypeConverter<AbstractModel> {}

  @TemporalTransferTypeConverter(MissingPublicConstructorConverter.class)
  public static final class MissingPublicConstructorModel {
    public MissingPublicConstructorModel() {}
  }

  public static final class MissingPublicConstructorConverter
      implements TransferTypeConverter<MissingPublicConstructorModel> {
    private MissingPublicConstructorConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return String.class;
    }

    @Override
    public Object toTransferType(MissingPublicConstructorModel value) {
      return "value";
    }

    @Override
    public MissingPublicConstructorModel fromTransferType(Object value, Type valueType) {
      return new MissingPublicConstructorModel();
    }
  }

  @TemporalTransferTypeConverter(ThrowingConstructorConverter.class)
  public static final class ThrowingConstructorModel {
    public ThrowingConstructorModel() {}
  }

  public static final class ThrowingConstructorConverter
      implements TransferTypeConverter<ThrowingConstructorModel> {
    public ThrowingConstructorConverter() {
      throw new IllegalStateException("constructor");
    }

    @Override
    public Type getTransferType(Type valueType) {
      return String.class;
    }

    @Override
    public Object toTransferType(ThrowingConstructorModel value) {
      return "value";
    }

    @Override
    public ThrowingConstructorModel fromTransferType(Object value, Type valueType) {
      return new ThrowingConstructorModel();
    }
  }

  @TemporalTransferTypeConverter(NullTransferConverter.class)
  public static final class NullTransferModel {}

  public static final class NullTransferConverter
      implements TransferTypeConverter<NullTransferModel> {
    public NullTransferConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return null;
    }

    @Override
    public Object toTransferType(NullTransferModel value) {
      return "value";
    }

    @Override
    public NullTransferModel fromTransferType(Object value, Type valueType) {
      return new NullTransferModel();
    }
  }

  @TemporalTransferTypeConverter(FailingConverter.class)
  public static final class FailingModel {}

  public static final class FailingConverter implements TransferTypeConverter<FailingModel> {
    public FailingConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return String.class;
    }

    @Override
    public Object toTransferType(FailingModel value) {
      throw new CallbackException("callback");
    }

    @Override
    public FailingModel fromTransferType(Object value, Type valueType) {
      throw new CallbackException("callback");
    }
  }

  private static final class CallbackException extends RuntimeException {
    private CallbackException(String message) {
      super(message);
    }
  }
}
