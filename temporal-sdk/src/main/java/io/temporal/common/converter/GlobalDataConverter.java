package io.temporal.common.converter;

import java.util.concurrent.atomic.AtomicReference;

public class GlobalDataConverter {
  private GlobalDataConverter() {}

  private static final AtomicReference<DataConverter> globalDataConverterInstance =
      new AtomicReference<>(DefaultDataConverter.STANDARD_INSTANCE);

  /**
   * Override the global data converter default.
   *
   * <p>Consider using {@link
   * io.temporal.client.WorkflowClientOptions.Builder#setDataConverter(DataConverter)} to set data
   * converter per client / worker instance to avoid conflicts if your setup requires different
   * converters for different clients / workers.
   */
  public static void register(DataConverter converter) {
    globalDataConverterInstance.set(converter);
  }

  public static DataConverter get() {
    return globalDataConverterInstance.get();
  }
}
