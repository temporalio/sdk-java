package io.temporal.spring.boot.autoconfigure;

import com.google.common.base.MoreObjects;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;

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

  /**
   * Create a comparator that can extract @Order and @Priority from beans in the given bean factory.
   * This is needed because the default OrderComparator doesn't know about the bean factory and
   * therefore can't look up annotations on beans.
   */
  private static Comparator<Object> beanFactoryAwareOrderComparator(
      ListableBeanFactory beanFactory) {
    return OrderComparator.INSTANCE.withSourceProvider(
        o -> {
          if (!(o instanceof Map.Entry)) {
            throw new IllegalStateException("Unexpected object type: " + o);
          }
          Map.Entry<String, TemporalOptionsCustomizer<?>> entry =
              (Map.Entry<String, TemporalOptionsCustomizer<?>>) o;
          // Check if the bean itself has a Priority annotation
          Integer priority = AnnotationAwareOrderComparator.INSTANCE.getPriority(entry.getValue());
          if (priority != null) {
            return (Ordered) () -> priority;
          }

          // Check if the bean factory method or the bean has an Order annotations
          String beanName = entry.getKey();
          if (beanName != null) {
            Order order = beanFactory.findAnnotationOnBean(beanName, Order.class);
            if (order != null) {
              return (Ordered) order::value;
            }
          }

          // Nothing present
          return null;
        });
  }

  static <T> List<TemporalOptionsCustomizer<T>> chooseTemporalCustomizerBeans(
      ListableBeanFactory beanFactory,
      Map<String, TemporalOptionsCustomizer<T>> customizerMap,
      Class<T> genericOptionsBuilderClass,
      TemporalProperties properties) {
    if (Objects.isNull(customizerMap) || customizerMap.isEmpty()) {
      return null;
    }
    List<NonRootNamespaceProperties> nonRootNamespaceProperties = properties.getNamespaces();
    Stream<Entry<String, TemporalOptionsCustomizer<T>>> customizerStream =
        customizerMap.entrySet().stream();
    if (!(Objects.isNull(nonRootNamespaceProperties) || nonRootNamespaceProperties.isEmpty())) {
      // Non-root namespace bean names, such as "nsWorkerFactoryCustomizer", "nsWorkerCustomizer"
      List<String> nonRootBeanNames =
          nonRootNamespaceProperties.stream()
              .map(
                  ns ->
                      temporalCustomizerBeanName(
                          MoreObjects.firstNonNull(ns.getAlias(), ns.getNamespace()),
                          genericOptionsBuilderClass))
              .collect(Collectors.toList());
      customizerStream =
          customizerStream.filter(entry -> !nonRootBeanNames.contains(entry.getKey()));
    }
    return customizerStream
        .sorted(beanFactoryAwareOrderComparator(beanFactory))
        .map(Entry::getValue)
        .collect(Collectors.toList());
  }

  static String temporalCustomizerBeanName(String beanPrefix, Class<?> optionsBuilderClass) {
    String builderCanonicalName = optionsBuilderClass.getCanonicalName();
    String bindingCustomizerName = builderCanonicalName.replace("Options.Builder", "Customizer");
    bindingCustomizerName =
        bindingCustomizerName.substring(bindingCustomizerName.lastIndexOf(".") + 1);
    return beanPrefix + bindingCustomizerName;
  }

  /**
   * Filter out plugins that implement a higher-level plugin interface, as those are handled at that
   * higher level via propagation.
   */
  static <T> @Nullable List<T> filterPlugins(@Nullable List<T> plugins, Class<?> excludeType) {
    if (plugins == null || plugins.isEmpty()) {
      return plugins;
    }
    List<T> filtered = new ArrayList<>();
    for (T plugin : plugins) {
      if (!excludeType.isInstance(plugin)) {
        filtered.add(plugin);
      }
    }
    return filtered.isEmpty() ? null : filtered;
  }
}
