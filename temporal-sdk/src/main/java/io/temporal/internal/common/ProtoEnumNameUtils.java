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

package io.temporal.internal.common;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMap;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Because in Go enums are in a shared namespace, one of protobuf common practices is to prefix the
 * enums with enum name prefix to create unique names. The final result is verbose and when we use a
 * string representation of some enum in different parts of the system (history json or search
 * attribute types), we use a short version without prefix in camel case. So, protobuf enum {@link
 * io.temporal.api.enums.v1.EventType#EVENT_TYPE_WORKFLOW_EXECUTION_STARTED} gets converted to
 * "WorkflowExecutionStarted"
 *
 * <p><a href="https://google.aip.dev/126">AIP 126</a> <br>
 * - The other values should not be prefixed by the name of the enum itself. This generally requires
 * users to write MyState.MYSTATE_ACTIVE in their code, which is unnecessarily verbose. - To avoid
 * sharing values, APIs may prefix enum values with the name of the enum. In this case, they must do
 * so consistently within the enum.
 *
 * @see <a href="https://google.aip.dev/126">AIP 126</a>
 * @see <a
 *     href="https://github.com/temporalio/gogo-protobuf/commit/b38fb010909b8f81e2e600dc6f04925fc71d6a5e">
 *     Related commit to Go Proto module</a>
 */
public class ProtoEnumNameUtils {
  public static final String WORKFLOW_TASK_FAILED_CAUSE_PREFIX = "WORKFLOW_TASK_FAILED_CAUSE_";
  public static final String INDEXED_VALUE_TYPE_PREFIX = "INDEXED_VALUE_TYPE_";

  public static final Map<Class<?>, String> ENUM_CLASS_TO_PREFIX =
      ImmutableMap.of(
          WorkflowTaskFailedCause.class, WORKFLOW_TASK_FAILED_CAUSE_PREFIX,
          IndexedValueType.class, INDEXED_VALUE_TYPE_PREFIX);

  @Nonnull
  public static String uniqueToSimplifiedName(@Nonnull Enum<?> enumm) {
    String protoEnumName = enumm.name();
    String prefix = ENUM_CLASS_TO_PREFIX.get(enumm.getClass());
    if (prefix == null) {
      throw new IllegalStateException(
          "Enum "
              + enumm.getClass()
              + " must be explicitly registered in #ENUM_CLASS_TO_PREFIX to be used in this method");
    }
    if (!protoEnumName.startsWith(prefix)) {
      throw new IllegalArgumentException("protoEnumName should start with " + prefix + " prefix");
    }
    protoEnumName = protoEnumName.substring(prefix.length());
    return screamingCaseEventTypeToCamelCase(protoEnumName);
  }

  public static String uniqueToSimplifiedName(String protoEnumName, String prefix) {
    if (!protoEnumName.startsWith(prefix)) {
      throw new IllegalArgumentException("protoEnumName should start with " + prefix + " prefix");
    }
    protoEnumName = protoEnumName.substring(prefix.length());
    return screamingCaseEventTypeToCamelCase(protoEnumName);
  }

  public static String simplifiedToUniqueName(String enumName, String prefix) {
    return prefix + camelCaseToScreamingCase(enumName);
  }

  // https://github.com/temporalio/gogo-protobuf/commit/b38fb010909b8f81e2e600dc6f04925fc71d6a5e
  private static String camelCaseToScreamingCase(String camel) {
    return CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.UPPER_UNDERSCORE).convert(camel);
  }

  // https://github.com/temporalio/gogo-protobuf/commit/b38fb010909b8f81e2e600dc6f04925fc71d6a5e
  private static String screamingCaseEventTypeToCamelCase(String screaming) {
    return CaseFormat.UPPER_UNDERSCORE.converterTo(CaseFormat.UPPER_CAMEL).convert(screaming);
  }
}
