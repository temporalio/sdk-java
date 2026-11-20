package io.temporal.spring.boot.autoconfigure.template;

import io.temporal.client.NexusClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.NexusClientInterceptor;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NexusClientOptionsTemplate {
  private final @Nonnull String namespace;
  private final @Nullable DataConverter dataConverter;
  private final @Nullable List<NexusClientInterceptor> interceptors;
  private final @Nullable List<TemporalOptionsCustomizer<NexusClientOptions.Builder>> customizers;

  public NexusClientOptionsTemplate(
      @Nonnull String namespace,
      @Nullable DataConverter dataConverter,
      @Nullable List<NexusClientInterceptor> interceptors,
      @Nullable List<TemporalOptionsCustomizer<NexusClientOptions.Builder>> customizers) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.interceptors = interceptors;
    this.customizers = customizers;
  }

  public NexusClientOptions createNexusClientOptions() {
    NexusClientOptions.Builder options = NexusClientOptions.newBuilder().setNamespace(namespace);
    if (dataConverter != null) {
      options.setDataConverter(dataConverter);
    }
    if (interceptors != null) {
      options.setInterceptors(interceptors);
    }
    if (customizers != null) {
      for (TemporalOptionsCustomizer<NexusClientOptions.Builder> customizer : customizers) {
        options = customizer.customize(options);
      }
    }
    return options.build();
  }
}
