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

package io.temporal.workflowcheck;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.annotation.Nullable;
import org.objectweb.asm.Type;

/** Helpers for printing results. */
class Printer {
  static String methodText(
      ClassInfo classInfo, String methodName, ClassInfo.MethodInfo methodInfo) {
    Printer printer = new Printer();
    printer.appendMethod(
        classInfo, methodName, methodInfo, "", Collections.newSetFromMap(new IdentityHashMap<>()));
    return printer.bld.toString();
  }

  private final StringBuilder bld = new StringBuilder();

  private void appendMethod(
      ClassInfo classInfo,
      String methodName,
      ClassInfo.MethodInfo methodInfo,
      String indent,
      Set<ClassInfo.MethodInfo> seenMethods) {
    seenMethods.add(methodInfo);
    bld.append(indent);
    if (methodInfo.workflowImpl != null) {
      bld.append("Workflow method ");
      appendFriendlyMember(classInfo.name, methodName, methodInfo.descriptor);
      bld.append(" (declared on ");
      appendFriendlyClassName(methodInfo.workflowImpl.declClassInfo.name);
      bld.append(")");
    } else {
      bld.append("Method ");
      appendFriendlyMember(classInfo.name, methodName, methodInfo.descriptor);
    }
    if (!methodInfo.isInvalid()) {
      bld.append(" is valid\n");
    } else if (methodInfo.configuredInvalid != null) {
      bld.append(" is configured as invalid\n");
    } else if (seenMethods.size() > 30) {
      bld.append(" is invalid (stack depth exceeded, stopping here)\n");
    } else if (methodInfo.invalidMemberAccesses != null) {
      bld.append(" has ")
          .append(methodInfo.invalidMemberAccesses.size())
          .append(" invalid member access");
      if (methodInfo.invalidMemberAccesses.size() > 1) {
        bld.append("es");
      }
      bld.append(":\n");
      for (ClassInfo.MethodInvalidMemberAccessInfo memberAccess :
          methodInfo.invalidMemberAccesses) {
        appendInvalidMemberAccess(classInfo, memberAccess, indent + "  ", seenMethods);
      }
    } else {
      // Should not happen
      bld.append(" is invalid for unknown reasons\n");
    }
    seenMethods.remove(methodInfo);
  }

  private void appendInvalidMemberAccess(
      ClassInfo callerClassInfo,
      ClassInfo.MethodInvalidMemberAccessInfo accessInfo,
      String indent,
      Set<ClassInfo.MethodInfo> seenMethods) {
    bld.append(indent);
    if (callerClassInfo.fileName == null) {
      bld.append("<unknown-file>");
    } else {
      bld.append(callerClassInfo.fileName);
      if (accessInfo.line != null) {
        bld.append(':').append(accessInfo.line);
      }
    }
    switch (accessInfo.operation) {
      case FIELD_CONFIGURED_INVALID:
        bld.append(" references ");
        appendFriendlyMember(accessInfo.className, accessInfo.memberName, null);
        bld.append(" which is configured as invalid\n");
        break;
      case FIELD_STATIC_GET:
        bld.append(" gets ");
        appendFriendlyMember(accessInfo.className, accessInfo.memberName, null);
        bld.append(" which is a non-final static field\n");
        break;
      case FIELD_STATIC_PUT:
        bld.append(" sets ");
        appendFriendlyMember(accessInfo.className, accessInfo.memberName, null);
        bld.append(" which is a non-final static field\n");
        break;
      case METHOD_CALL:
        bld.append(" invokes ");
        appendFriendlyMember(
            accessInfo.className, accessInfo.memberName, accessInfo.memberDescriptor);
        if (accessInfo.resolvedInvalidClass == null) {
          // Should never happen
          bld.append(" (resolution failed)\n");
        } else if (accessInfo.resolvedInvalidMethod == null) {
          bld.append(" which is configured as invalid\n");
        } else if (seenMethods.contains(accessInfo.resolvedInvalidMethod)) {
          // Should not happen
          bld.append(" (unexpected recursion)\n");
        } else {
          bld.append(":\n");
          appendMethod(
              accessInfo.resolvedInvalidClass,
              accessInfo.memberName,
              accessInfo.resolvedInvalidMethod,
              indent + "  ",
              seenMethods);
        }
        break;
    }
  }

  private void appendFriendlyClassName(String className) {
    bld.append(className.replace('/', '.'));
  }

  private void appendFriendlyMember(
      String className, String memberName, @Nullable String methodDescriptor) {
    appendFriendlyClassName(className);
    bld.append('.').append(memberName);
    if (methodDescriptor != null) {
      bld.append('(');
      Type[] argTypes = Type.getArgumentTypes(methodDescriptor);
      for (int i = 0; i < argTypes.length; i++) {
        if (i > 0) {
          bld.append(", ");
        }
        bld.append(argTypes[i].getClassName());
      }
      bld.append(')');
    }
  }
}
