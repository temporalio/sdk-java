/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
  private static final String KEY = "spring.temporal.workers";

  public WorkersPresentCondition() {}

  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    BindResult<?> property = Binder.get(context.getEnvironment()).bind(KEY, WORKER_PROPERTIES_LIST);
    ConditionMessage.Builder messageBuilder = ConditionMessage.forCondition("Present Workers");
    if (property.isBound()) {
      return ConditionOutcome.match(messageBuilder.found("property").items(KEY));
    }
    return ConditionOutcome.noMatch(messageBuilder.didNotFind("property").items(KEY));
  }
}
