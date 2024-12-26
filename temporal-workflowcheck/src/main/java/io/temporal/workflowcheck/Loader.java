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

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Loader that loads the classes, caches them, and does the work to determine invalidity across
 * classes (and clean up the classes).
 */
class Loader {
  private final Config config;
  private final ClassPath classPath;
  private final Map<String, ClassInfo> classes = new HashMap<>();

  Loader(Config config, ClassPath classPath) {
    this.config = config;
    this.classPath = classPath;
  }

  ClassInfo loadClass(String className) {
    return classes.computeIfAbsent(
        className,
        v -> {
          try (InputStream is = classPath.classLoader.getResourceAsStream(className + ".class")) {
            if (is == null) {
              // We are going to just make a dummy when we can't find a class
              // TODO(cretz): Warn?
              ClassInfo info = new ClassInfo();
              info.access = Opcodes.ACC_SYNTHETIC;
              info.name = className;
              return info;
            }
            ClassInfoVisitor visitor = new ClassInfoVisitor(config);
            new ClassReader(is).accept(visitor, ClassReader.SKIP_FRAMES);
            return visitor.classInfo;
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Nullable
  ClassInfo.MethodWorkflowImplInfo findWorkflowImplInfo(
      ClassInfo on, String implClassName, String implMethodName, String implMethodDescriptor) {
    // Check my own methods
    List<ClassInfo.MethodInfo> methods = on.methods.get(implMethodName);
    if (methods != null) {
      for (ClassInfo.MethodInfo method : methods) {
        if (method.workflowDecl != null
            && isMethodOverride(on, method, implClassName, implMethodDescriptor)) {
          return new ClassInfo.MethodWorkflowImplInfo(on, method.workflowDecl);
        }
      }
    }
    // Check super class then super interfaces (we don't care about the
    // potential duplicate checks, better than maintaining an already-seen map)
    if (on.superClass != null && !ClassPath.isStandardLibraryClass(on.superClass)) {
      ClassInfo.MethodWorkflowImplInfo info =
          findWorkflowImplInfo(
              loadClass(on.superClass), implClassName, implMethodName, implMethodDescriptor);
      if (info != null) {
        return info;
      }
    }
    if (on.superInterfaces != null) {
      for (String iface : on.superInterfaces) {
        if (!ClassPath.isStandardLibraryClass(iface)) {
          ClassInfo.MethodWorkflowImplInfo info =
              findWorkflowImplInfo(
                  loadClass(iface), implClassName, implMethodName, implMethodDescriptor);
          if (info != null) {
            return info;
          }
        }
      }
    }
    return null;
  }

  void processMethodValidity(ClassInfo.MethodInfo method, Set<ClassInfo.MethodInfo> processing) {
    // If it has no member accesses (possibly actually has no calls/fields or
    // just has configured-invalid already set) or already processed, do
    // nothing. This of course means that recursion does not apply for
    // invalidity.
    if (method.memberAccesses == null || processing.contains(method)) {
      return;
    }
    // Go over every call and check whether invalid
    processing.add(method);
    for (ClassInfo.MethodInvalidMemberAccessInfo memberAccess : method.memberAccesses) {
      boolean invalid = false;
      switch (memberAccess.operation) {
        case FIELD_CONFIGURED_INVALID:
          // This is always considered invalid
          invalid = true;
          break;
        case FIELD_STATIC_GET:
        case FIELD_STATIC_PUT:
          // This is considered invalid if the class has the field as a
          // non-final static
          memberAccess.resolvedInvalidClass = loadClass(memberAccess.className);
          invalid =
              memberAccess.resolvedInvalidClass.nonFinalStaticFields != null
                  && memberAccess.resolvedInvalidClass.nonFinalStaticFields.contains(
                      memberAccess.memberName);
          break;
        case METHOD_CALL:
          // A call is considered invalid/valid if:
          // * Configured invalid set in the hierarchy (most-specific wins)
          // * Actual impl of the method has invalid calls
          ClassInfo callClass = loadClass(memberAccess.className);

          ConfiguredInvalidResolution configResolution = new ConfiguredInvalidResolution();
          resolveConfiguredInvalid(
              callClass,
              memberAccess.memberName,
              memberAccess.memberDescriptor,
              0,
              configResolution);
          if (configResolution.value != null) {
            if (configResolution.value) {
              memberAccess.resolvedInvalidClass = configResolution.classFoundOn;
              invalid = true;
            }
            break;
          }

          MethodResolution methodResolution = new MethodResolution();
          resolveMethod(
              loadClass(memberAccess.className),
              memberAccess.className,
              memberAccess.memberName,
              memberAccess.memberDescriptor,
              methodResolution);
          if (methodResolution.implClass != null) {
            // Process invalidity on this method, then check if it's invalid
            processMethodValidity(methodResolution.implMethod, processing);
            if (methodResolution.implMethod.isInvalid()) {
              memberAccess.resolvedInvalidClass = methodResolution.implClass;
              memberAccess.resolvedInvalidMethod = methodResolution.implMethod;
              invalid = true;
            }
          }
          break;
      }
      if (invalid) {
        if (method.invalidMemberAccesses == null) {
          method.invalidMemberAccesses = new ArrayList<>(1);
        }
        method.invalidMemberAccesses.add(memberAccess);
      }
    }
    // Unset the member accesses now that we've processed them
    method.memberAccesses = null;
    // Sort invalid accesses if there are any
    if (method.invalidMemberAccesses != null) {
      method.invalidMemberAccesses.sort(Comparator.comparingInt(m -> m.line == null ? -1 : m.line));
    }
    processing.remove(method);
  }

  private static class ConfiguredInvalidResolution {
    private ClassInfo classFoundOn;
    private int depthFoundOn;
    private Boolean value;
  }

  private void resolveConfiguredInvalid(
      ClassInfo on,
      String methodName,
      String methodDescriptor,
      int depth,
      ConfiguredInvalidResolution resolution) {
    // First check myself
    Boolean configuredInvalid = config.invalidMembers.check(on.name, methodName, methodDescriptor);
    if (configuredInvalid != null
        && isMoreSpecific(resolution.classFoundOn, resolution.depthFoundOn, on, depth)) {
      resolution.classFoundOn = on;
      resolution.depthFoundOn = depth;
      resolution.value = configuredInvalid;
    }

    // Now check super class and super interfaces. We don't care enough to
    // prevent re-checking diamonds.
    if (on.superClass != null) {
      resolveConfiguredInvalid(
          loadClass(on.superClass), methodName, methodDescriptor, depth + 1, resolution);
    }
    if (on.superInterfaces != null) {
      for (String iface : on.superInterfaces) {
        resolveConfiguredInvalid(
            loadClass(iface), methodName, methodDescriptor, depth + 1, resolution);
      }
    }
  }

  private static class MethodResolution {
    ClassInfo implClass;
    ClassInfo.MethodInfo implMethod;
  }

  private void resolveMethod(
      ClassInfo on,
      String callClassName,
      String callMethodName,
      String callMethodDescriptor,
      MethodResolution resolution) {
    // First, see if the method is even on this class
    List<ClassInfo.MethodInfo> methods = on.methods.get(callMethodName);
    if (methods != null) {
      for (ClassInfo.MethodInfo method : methods) {
        // Only methods with bodies apply
        if ((method.access & Opcodes.ACC_ABSTRACT) != 0
            || (method.access & Opcodes.ACC_NATIVE) != 0) {
          continue;
        }
        // To qualify, method descriptor must match if same call class name, or
        // method must be an override if different call class name
        if ((callClassName.equals(on.name) && method.descriptor.equals(callMethodDescriptor))
            || isMethodOverride(on, method, callClassName, callMethodDescriptor)) {
          // If we have a body and impl hasn't been sent, this is the impl.
          // Otherwise, we have to check whether it's more specific. Depth does
          // not matter because Java compiler won't allow ambiguity here (i.e.
          // multiple unrelated interface defaults).
          if (isMoreSpecific(resolution.implClass, 0, on, 0)) {
            resolution.implClass = on;
            resolution.implMethod = method;
            // If this is not an interface, we're done trying to find others
            if ((method.access & Opcodes.ACC_INTERFACE) == 0) {
              return;
            }
          }
          break;
        }
      }
    }

    // Now check super class and super interfaces. We don't care enough to
    // prevent re-checking diamonds.
    if (on.superClass != null) {
      resolveMethod(
          loadClass(on.superClass),
          callClassName,
          callMethodName,
          callMethodDescriptor,
          resolution);
    }
    if (on.superInterfaces != null) {
      for (String iface : on.superInterfaces) {
        resolveMethod(
            loadClass(iface), callClassName, callMethodName, callMethodDescriptor, resolution);
      }
    }
  }

  private boolean isMoreSpecific(
      @Nullable ClassInfo prevClass, int prevDepth, ClassInfo newClass, int newDepth) {
    // If there is no prev, this is always more specific
    if (prevClass == null) {
      return true;
    }

    // If the prev class is not an interface, it is always more specific, then
    // apply that logic to new over any interface that may have been seen
    if ((prevClass.access & Opcodes.ACC_INTERFACE) == 0) {
      return false;
    } else if ((newClass.access & Opcodes.ACC_INTERFACE) == 0) {
      return true;
    }

    // Now that we know they are both interfaces, if the new class is a
    // sub-interface of the prev class, it is more specific. For default-method
    // resolution purposes, Java would disallow two independent implementations
    // of the same default method on independent interfaces. But this isn't for
    // default purposes, so there can be multiple. In this rare case, we will
    // choose which has the least depth, and in the rarer case they are the
    // same depth, we just leave previous.
    if (isAssignableFrom(prevClass.name, newClass)) {
      return true;
    } else if (!isAssignableFrom(newClass.name, prevClass)) {
      return false;
    }
    return newDepth < prevDepth;
  }

  // Expects name check to already be done
  private boolean isMethodOverride(
      ClassInfo superClass,
      ClassInfo.MethodInfo superMethod,
      // If null, package-private not verified
      @Nullable String subClassName,
      String subMethodDescriptor) {
    // Final, static, or private are never inherited
    int superAccess = superMethod.access;
    if ((superAccess & Opcodes.ACC_FINAL) != 0
        || (superAccess & Opcodes.ACC_STATIC) != 0
        || (superAccess & Opcodes.ACC_PRIVATE) != 0) {
      return false;
    }
    // Package-private only inherited if same package
    if (subClassName != null
        && (superAccess & Opcodes.ACC_PUBLIC) == 0
        && (superAccess & Opcodes.ACC_PROTECTED) == 0) {
      int slashIndex = superClass.name.lastIndexOf('/');
      if (slashIndex == 0
          || !subClassName.startsWith(superClass.name.substring(0, slashIndex + 1))) {
        return false;
      }
    }
    // Check descriptor. This can have a covariant return, so this must check
    // exact args first then return covariance.
    String superDesc = superMethod.descriptor;
    // Simple equality perf shortcut
    if (superDesc.equals(subMethodDescriptor)) {
      return true;
    }
    // Since it didn't match exact, check up to end paren if both have ")L"
    int endParen = superDesc.lastIndexOf(')');
    if (endParen >= subMethodDescriptor.length()
        || subMethodDescriptor.charAt(endParen) != ')'
        || superDesc.charAt(endParen + 1) != 'L'
        || subMethodDescriptor.charAt(endParen + 1) != 'L') {
      return false;
    }
    // Check args
    if (!subMethodDescriptor.regionMatches(0, superMethod.descriptor, 0, endParen + 1)) {
      return false;
    }
    // Check super return is same or super of sub return (after 'L', before end ';')
    return isAssignableFrom(
        superMethod.descriptor.substring(endParen + 2, superMethod.descriptor.length() - 1),
        subMethodDescriptor.substring(endParen + 2, subMethodDescriptor.length() - 1));
  }

  private boolean isAssignableFrom(String sameOrSuperOfSubject, String subject) {
    if (sameOrSuperOfSubject.equals(subject)) {
      return true;
    }
    return isAssignableFrom(sameOrSuperOfSubject, loadClass(subject));
  }

  private boolean isAssignableFrom(String sameOrSuperOfSubject, ClassInfo subject) {
    if (sameOrSuperOfSubject.equals(subject.name)) {
      return true;
    }
    if (sameOrSuperOfSubject.equals(subject.superClass)) {
      return true;
    }
    if (subject.superInterfaces != null) {
      for (String iface : subject.superInterfaces) {
        if (sameOrSuperOfSubject.equals(iface)) {
          return true;
        }
      }
    }
    // Since there were no direct matches, now check if subject super classes
    // or interfaces match
    if (subject.superClass != null) {
      if (isAssignableFrom(sameOrSuperOfSubject, loadClass(subject.superClass))) {
        return true;
      }
    }
    if (subject.superInterfaces != null) {
      for (String iface : subject.superInterfaces) {
        if (isAssignableFrom(sameOrSuperOfSubject, loadClass(iface))) {
          return true;
        }
      }
    }
    return false;
  }
}
