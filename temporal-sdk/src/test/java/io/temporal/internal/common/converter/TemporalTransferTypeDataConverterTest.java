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
  }

  @Test
  public void nullTransferValueIsReconstructed() {
    Payload payload = converter.toPayload(new NullRepresentationModel()).get();

    NullRepresentationModel restored =
        converter.fromPayload(
            payload, NullRepresentationModel.class, NullRepresentationModel.class);

    assertEquals(
        DefaultDataConverter.STANDARD_PAYLOAD_CONVERTERS[0].getEncodingType(),
        payload.getMetadataOrThrow("encoding").toStringUtf8());
    assertTrue(restored.reconstructed);
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
  public void inboundLookupDoesNotInheritBaseConverter() {
    DataConverter delegate = mock(DataConverter.class);
    DataConverter transferAware = TemporalTransferTypeDataConverter.wrap(delegate);
    Payload payload = Payload.getDefaultInstance();
    DerivedWithoutAnnotation expected = new DerivedWithoutAnnotation("value");
    when(delegate.fromPayload(
            payload, DerivedWithoutAnnotation.class, DerivedWithoutAnnotation.class))
        .thenReturn(expected);

    DerivedWithoutAnnotation actual =
        transferAware.fromPayload(
            payload, DerivedWithoutAnnotation.class, DerivedWithoutAnnotation.class);

    assertSame(expected, actual);
    verify(delegate)
        .fromPayload(payload, DerivedWithoutAnnotation.class, DerivedWithoutAnnotation.class);
  }

  @Test
  public void conversionIsTopLevelAndPerformsOneTransferStep() {
    SecondStepConverter.invocations.set(0);
    NestedModelConverter.invocations.set(0);

    FirstStepModel firstStep =
        converter.fromPayload(
            converter.toPayload(new FirstStepModel("one")).get(),
            FirstStepModel.class,
            FirstStepModel.class);
    OrdinaryContainer container =
        converter.fromPayload(
            converter.toPayload(new OrdinaryContainer(new NestedModel("two"))).get(),
            OrdinaryContainer.class,
            OrdinaryContainer.class);

    assertEquals("one", firstStep.value);
    assertEquals(0, SecondStepConverter.invocations.get());
    assertEquals("two", container.value.value);
    assertEquals(0, NestedModelConverter.invocations.get());
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
  public void classIsUsedWhenRequestedTypeIsAbsent() {
    Payload payload = converter.toPayload(new Model("value")).get();

    assertEquals(new Model("value"), converter.fromPayload(payload, Model.class, null));
    assertSame(Model.class, ModelConverter.lastTransferTypeRequest);
    assertSame(Model.class, ModelConverter.lastConversionRequest);

    Optional<Payloads> payloads = converter.toPayloads(new Model("value"));
    assertEquals(new Model("value"), converter.fromPayloads(0, payloads, Model.class, null));
    assertSame(Model.class, ModelConverter.lastTransferTypeRequest);
    assertSame(Model.class, ModelConverter.lastConversionRequest);

    Object[] values =
        converter.fromPayloads(payloads, new Class<?>[] {Model.class}, new Type[] {null});
    assertEquals(new Model("value"), values[0]);
    assertSame(Model.class, ModelConverter.lastTransferTypeRequest);
    assertSame(Model.class, ModelConverter.lastConversionRequest);
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
    static Type lastTransferTypeRequest;
    static Type lastConversionRequest;

    public ModelConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      lastTransferTypeRequest = valueType;
      return StringValue.class;
    }

    @Override
    public Object toTransferType(Model value) {
      return StringValue.of(value.value);
    }

    @Override
    public Model fromTransferType(Object value, Type valueType) {
      lastConversionRequest = valueType;
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

  @TemporalTransferTypeConverter(NullRepresentationConverter.class)
  public static final class NullRepresentationModel {
    private final boolean reconstructed;

    public NullRepresentationModel() {
      this(false);
    }

    private NullRepresentationModel(boolean reconstructed) {
      this.reconstructed = reconstructed;
    }
  }

  public static final class NullRepresentationConverter
      implements TransferTypeConverter<NullRepresentationModel> {
    public NullRepresentationConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return String.class;
    }

    @Override
    public Object toTransferType(NullRepresentationModel value) {
      return null;
    }

    @Override
    public NullRepresentationModel fromTransferType(Object value, Type valueType) {
      assertNull(value);
      return new NullRepresentationModel(true);
    }
  }

  @TemporalTransferTypeConverter(FirstStepConverter.class)
  public static final class FirstStepModel {
    private final String value;

    private FirstStepModel(String value) {
      this.value = value;
    }
  }

  public static final class FirstStepConverter implements TransferTypeConverter<FirstStepModel> {
    public FirstStepConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      return SecondStepModel.class;
    }

    @Override
    public Object toTransferType(FirstStepModel value) {
      return new SecondStepModel(value.value);
    }

    @Override
    public FirstStepModel fromTransferType(Object value, Type valueType) {
      return new FirstStepModel(((SecondStepModel) value).value);
    }
  }

  @TemporalTransferTypeConverter(SecondStepConverter.class)
  public static final class SecondStepModel {
    public String value;

    public SecondStepModel() {}

    private SecondStepModel(String value) {
      this.value = value;
    }
  }

  public static final class SecondStepConverter implements TransferTypeConverter<SecondStepModel> {
    private static final AtomicInteger invocations = new AtomicInteger();

    public SecondStepConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      invocations.incrementAndGet();
      return String.class;
    }

    @Override
    public Object toTransferType(SecondStepModel value) {
      invocations.incrementAndGet();
      return value.value;
    }

    @Override
    public SecondStepModel fromTransferType(Object value, Type valueType) {
      invocations.incrementAndGet();
      return new SecondStepModel((String) value);
    }
  }

  public static final class OrdinaryContainer {
    public NestedModel value;

    public OrdinaryContainer() {}

    private OrdinaryContainer(NestedModel value) {
      this.value = value;
    }
  }

  @TemporalTransferTypeConverter(NestedModelConverter.class)
  public static final class NestedModel {
    public String value;

    public NestedModel() {}

    private NestedModel(String value) {
      this.value = value;
    }
  }

  public static final class NestedModelConverter implements TransferTypeConverter<NestedModel> {
    private static final AtomicInteger invocations = new AtomicInteger();

    public NestedModelConverter() {}

    @Override
    public Type getTransferType(Type valueType) {
      invocations.incrementAndGet();
      return String.class;
    }

    @Override
    public Object toTransferType(NestedModel value) {
      invocations.incrementAndGet();
      return value.value;
    }

    @Override
    public NestedModel fromTransferType(Object value, Type valueType) {
      invocations.incrementAndGet();
      return new NestedModel((String) value);
    }
  }

  private static final class CallbackException extends RuntimeException {
    private CallbackException(String message) {
      super(message);
    }
  }
}
