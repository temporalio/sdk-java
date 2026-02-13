package io.temporal.spring.boot.autoconfigure;

import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that checks if the "spring.temporal.namespaces" property is present in the application
 * context.
 */
class NamespacesPresentCondition extends SpringBootCondition {
  private static final Bindable<List<NonRootNamespaceProperties>> NAMESPACES_LIST =
      Bindable.listOf(NonRootNamespaceProperties.class);
  private static final String NAMESPACES_KEY = "spring.temporal.namespaces";

  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    BindResult<?> namespacesProperty =
        Binder.get(context.getEnvironment()).bind(NAMESPACES_KEY, NAMESPACES_LIST);
    ConditionMessage.Builder messageBuilder = ConditionMessage.forCondition("Present namespaces");
    if (namespacesProperty.isBound()) {
      return ConditionOutcome.match(messageBuilder.found("property").items(NAMESPACES_KEY));
    }
    return ConditionOutcome.noMatch(messageBuilder.didNotFind("property").items(NAMESPACES_KEY));
  }
}
