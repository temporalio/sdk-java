package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.NamespaceTemplate;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

@ExtendWith(MockitoExtension.class)
public class NonRootBeanPostProcessorTest {

  @Mock private TemporalProperties temporalProperties;
  @Mock private ConfigurableListableBeanFactory beanFactory;
  @Mock private NamespaceTemplate rootNamespaceTemplate;

  private NonRootBeanPostProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new NonRootBeanPostProcessor(temporalProperties);
    processor.setBeanFactory(beanFactory);
  }

  @Test
  void shouldNotProcessBeansWhenNoNamespaces() {
    when(temporalProperties.getNamespaces()).thenReturn(null);
    Object result =
        processor.postProcessAfterInitialization(
            rootNamespaceTemplate, "temporalRootNamespaceTemplate");
    assertThat(result).isSameAs(rootNamespaceTemplate);
  }

  @Test
  void shouldNotProcessBeansWhenEmptyNamespaces() {
    when(temporalProperties.getNamespaces()).thenReturn(Collections.emptyList());
    Object result =
        processor.postProcessAfterInitialization(
            rootNamespaceTemplate, "temporalRootNamespaceTemplate");
    assertThat(result).isSameAs(rootNamespaceTemplate);
  }

  @Test
  void shouldOnlyProcessRootNamespaceTemplate() {
    Object otherBean = new Object();
    Object result = processor.postProcessAfterInitialization(otherBean, "someOtherBean");
    assertThat(result).isSameAs(otherBean);
  }

  @Test
  void shouldProcessOnlyOnceEvenWithMultipleCalls() {
    when(temporalProperties.getNamespaces()).thenReturn(Collections.emptyList());
    Object result1 =
        processor.postProcessAfterInitialization(
            rootNamespaceTemplate, "temporalRootNamespaceTemplate");
    Object result2 =
        processor.postProcessAfterInitialization(
            rootNamespaceTemplate, "temporalRootNamespaceTemplate");
    assertThat(result1).isSameAs(rootNamespaceTemplate);
    assertThat(result2).isSameAs(rootNamespaceTemplate);
  }

  @Test
  void shouldReturnOriginalBeanUnchanged() {
    when(temporalProperties.getNamespaces()).thenReturn(Collections.emptyList());
    Object result =
        processor.postProcessAfterInitialization(
            rootNamespaceTemplate, "temporalRootNamespaceTemplate");
    assertThat(result).isSameAs(rootNamespaceTemplate);
  }

  @Test
  void shouldHandleNullNamespaceGracefully() {
    when(temporalProperties.getNamespaces()).thenReturn(null);
    Object result =
        processor.postProcessAfterInitialization(
            rootNamespaceTemplate, "temporalRootNamespaceTemplate");
    assertThat(result).isSameAs(rootNamespaceTemplate);
  }
}
