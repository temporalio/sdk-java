package io.temporal.spring.boot.autoconfigure;

import com.google.common.base.MoreObjects;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;

class AutoConfigurationUtils {

  @Nullable
  static DataConverter chooseDataConverter(
      List<DataConverter> dataConverters, DataConverter mainDataConverter) {
    DataConverter chosenDataConverter = null;
    if (dataConverters.size() == 1) {
      chosenDataConverter = dataConverters.get(0);
    } else if (dataConverters.size() > 1) {
      if (mainDataConverter != null) {
        chosenDataConverter = mainDataConverter;
      } else {
        throw new NoUniqueBeanDefinitionException(
            DataConverter.class,
            dataConverters.size(),
            "Several DataConverter beans found in the Spring context. "
                + "Explicitly name 'mainDataConverter' the one bean "
                + "that should be used by Temporal Spring Boot AutoConfiguration.");
      }
    }
    return chosenDataConverter;
  }

  @Nullable
  static DataConverter chooseDataConverter(
      Map<String, DataConverter> dataConverters,
      DataConverter mainDataConverter,
      TemporalProperties properties) {
    if (Objects.isNull(dataConverters) || dataConverters.isEmpty()) {
      return null;
    }
    List<NonRootNamespaceProperties> nonRootNamespaceProperties = properties.getNamespaces();
    if (Objects.isNull(nonRootNamespaceProperties) || nonRootNamespaceProperties.isEmpty()) {
      return chooseDataConverter(new ArrayList<>(dataConverters.values()), mainDataConverter);
    } else {
      List<DataConverter> dataConverterList = new ArrayList<>();
      List<String> nonRootBeanNames =
          nonRootNamespaceProperties.stream()
              .map(
                  ns ->
                      MoreObjects.firstNonNull(ns.getAlias(), ns.getNamespace())
                          + DataConverter.class.getSimpleName())
              .collect(Collectors.toList());
      for (Entry<String, DataConverter> dataConverterEntry : dataConverters.entrySet()) {
        String beanName = dataConverterEntry.getKey();
        DataConverter dataConverter = dataConverterEntry.getValue();
        if (beanName.equals("mainDataConverter")) {
          continue;
        }
        // Indicate its non-root namespace data converter, skip it
        if (nonRootBeanNames.contains(beanName)) {
          continue;
        }
        dataConverterList.add(dataConverter);
      }
      return chooseDataConverter(dataConverterList, mainDataConverter);
    }
  }

  @Nullable
  static List<WorkflowClientInterceptor> chooseWorkflowClientInterceptors(
      List<WorkflowClientInterceptor> workflowClientInterceptors, TemporalProperties properties) {
    return workflowClientInterceptors;
  }

  @Nullable
  static List<ScheduleClientInterceptor> chooseScheduleClientInterceptors(
      List<ScheduleClientInterceptor> scheduleClientInterceptor, TemporalProperties properties) {
    return scheduleClientInterceptor;
  }

  @Nullable
  static List<WorkerInterceptor> chooseWorkerInterceptors(
      List<WorkerInterceptor> workerInterceptor, TemporalProperties properties) {
    return workerInterceptor;
  }

  static <T> TemporalOptionsCustomizer<T> chooseTemporalCustomizerBean(
      Map<String, TemporalOptionsCustomizer<T>> customizerMap,
      Class<T> genericOptionsBuilderClass,
      TemporalProperties properties) {
    if (Objects.isNull(customizerMap) || customizerMap.isEmpty()) {
      return null;
    }
    List<NonRootNamespaceProperties> nonRootNamespaceProperties = properties.getNamespaces();
    if (Objects.isNull(nonRootNamespaceProperties) || nonRootNamespaceProperties.isEmpty()) {
      return customizerMap.values().stream().findFirst().orElse(null);
    }
    // Non-root namespace bean names, such as "nsWorkerFactoryCustomizer", "nsWorkerCustomizer"
    List<String> nonRootBeanNames =
        nonRootNamespaceProperties.stream()
            .map(
                ns ->
                    temporalCustomizerBeanName(
                        MoreObjects.firstNonNull(ns.getAlias(), ns.getNamespace()),
                        genericOptionsBuilderClass))
            .collect(Collectors.toList());

    return customizerMap.entrySet().stream()
        .filter(entry -> !nonRootBeanNames.contains(entry.getKey()))
        .findFirst()
        .map(Entry::getValue)
        .orElse(null);
  }

  static String temporalCustomizerBeanName(String beanPrefix, Class<?> optionsBuilderClass) {
    String builderCanonicalName = optionsBuilderClass.getCanonicalName();
    String bindingCustomizerName = builderCanonicalName.replace("Options.Builder", "Customizer");
    bindingCustomizerName =
        bindingCustomizerName.substring(bindingCustomizerName.lastIndexOf(".") + 1);
    return beanPrefix + bindingCustomizerName;
  }
}
