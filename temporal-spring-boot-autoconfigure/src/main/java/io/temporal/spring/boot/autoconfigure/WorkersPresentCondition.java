package io.temporal.spring.boot.autoconfigure;

import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

class WorkersPresentCondition extends SpringBootCondition {
  private static final Bindable<List<WorkerProperties>> WORKER_PROPERTIES_LIST =
      Bindable.listOf(WorkerProperties.class);

  private static final Bindable<List<String>> AUTO_DISCOVERY_PACKAGES_LIST =
      Bindable.listOf(String.class);
  private static final String WORKERS_KEY = "spring.temporal.workers";
  private static final String AUTO_DISCOVERY_KEY =
      "spring.temporal.workers-auto-discovery.packages";

  public WorkersPresentCondition() {}

  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    BindResult<?> workersProperty =
        Binder.get(context.getEnvironment()).bind(WORKERS_KEY, WORKER_PROPERTIES_LIST);
    ConditionMessage.Builder messageBuilder = ConditionMessage.forCondition("Present Workers");
    if (workersProperty.isBound()) {
      return ConditionOutcome.match(messageBuilder.found("property").items(WORKERS_KEY));
    }

    BindResult<?> autoDiscoveryProperty =
        Binder.get(context.getEnvironment()).bind(AUTO_DISCOVERY_KEY, AUTO_DISCOVERY_PACKAGES_LIST);
    messageBuilder = ConditionMessage.forCondition("Auto Discovery Packages Set");
    if (autoDiscoveryProperty.isBound()) {
      return ConditionOutcome.match(messageBuilder.found("property").items(AUTO_DISCOVERY_KEY));
    }

    return ConditionOutcome.noMatch(
        messageBuilder.didNotFind("property").items(WORKERS_KEY, AUTO_DISCOVERY_KEY));
  }
}
