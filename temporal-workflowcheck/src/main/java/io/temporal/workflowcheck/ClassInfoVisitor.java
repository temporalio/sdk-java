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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.objectweb.asm.*;

/**
 * Visitor that visits the bytecode of a class. This is intentionally meant to be fast and have no
 * recursion or other reliance on the visiting of other classes. Successive phases tie class
 * information together.
 */
class ClassInfoVisitor extends ClassVisitor {
  // Visible for testing
  static final Logger logger = Logger.getLogger(ClassInfoVisitor.class.getName());

  final ClassInfo classInfo = new ClassInfo();
  private final Config config;
  private final MethodHandler methodHandler = new MethodHandler();
  @Nullable private SuppressionStack suppressionStack;

  ClassInfoVisitor(Config config) {
    super(Opcodes.ASM9);
    this.config = config;
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    classInfo.access = access;
    classInfo.name = name;
    classInfo.superClass = superName;
    classInfo.superInterfaces = interfaces;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
    return maybeSuppressionAttributeHandler(descriptor);
  }

  @Override
  public void visitSource(String source, String debug) {
    classInfo.fileName = source;
  }

  @Override
  public FieldVisitor visitField(
      int access, String name, String descriptor, String signature, Object value) {
    // Record all static non-final fields
    if ((access & Opcodes.ACC_FINAL) == 0 && (access & Opcodes.ACC_STATIC) != 0) {
      if (classInfo.nonFinalStaticFields == null) {
        classInfo.nonFinalStaticFields = new HashSet<>();
      }
      classInfo.nonFinalStaticFields.add(name);
    }

    // TODO(cretz): Support suppression attributes on static non-final fields
    return null;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String descriptor, String signature, String[] exceptions) {
    // Add method to class
    ClassInfo.MethodInfo methodInfo =
        new ClassInfo.MethodInfo(
            access, descriptor, config.invalidMembers.check(classInfo.name, name, descriptor));
    classInfo.methods.computeIfAbsent(name, k -> new ArrayList<>()).add(methodInfo);

    // Reset and reuse the handler
    methodHandler.reset(name, methodInfo);
    return methodHandler;
  }

  private AnnotationVisitor maybeSuppressionAttributeHandler(String descriptor) {
    if (descriptor.equals("Lio/temporal/workflowcheck/WorkflowCheck$SuppressWarnings;")) {
      return new SuppressionAttributeHandler();
    }
    return null;
  }

  private class SuppressionAttributeHandler extends AnnotationVisitor {
    private final List<String> specificDescriptors = new ArrayList<>();

    SuppressionAttributeHandler() {
      super(Opcodes.ASM9);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      return this;
    }

    @Override
    public void visit(String name, Object value) {
      // For now there is only one annotation param possible
      if (value instanceof String) {
        specificDescriptors.add((String) value);
      }
    }

    @Override
    public void visitEnd() {
      if (suppressionStack == null) {
        suppressionStack = new SuppressionStack();
      }
      suppressionStack.push(
          specificDescriptors.isEmpty() ? null : specificDescriptors.toArray(new String[0]));
    }
  }

  private class MethodHandler extends MethodVisitor {
    private String methodName;
    private ClassInfo.MethodInfo methodInfo;
    @Nullable private Integer methodLineNumber;
    private int methodSuppressions;
    private boolean methodSuppressionAnnotation;
    @Nullable private String prevInsnLdcString;

    MethodHandler() {
      super(Opcodes.ASM9);
    }

    void reset(String methodName, ClassInfo.MethodInfo methodInfo) {
      this.methodName = methodName;
      this.methodInfo = methodInfo;
      this.methodLineNumber = null;
      this.methodSuppressions = 0;
      this.methodSuppressionAnnotation = false;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      // Check if suppression annotation
      AnnotationVisitor suppressionVisitor = maybeSuppressionAttributeHandler(descriptor);
      if (suppressionVisitor != null) {
        methodSuppressions++;
        methodSuppressionAnnotation = true;
        return suppressionVisitor;
      }

      // If this descriptor is a known workflow decl kind, set as a decl
      ClassInfo.MethodWorkflowDeclInfo.Kind declKind =
          ClassInfo.MethodWorkflowDeclInfo.Kind.annotationDescriptors.get(descriptor);
      if (declKind != null) {
        if (logger.isLoggable(Level.FINE)) {
          logger.log(
              Level.FINE,
              "Found workflow method decl on {0}.{1}",
              new Object[] {classInfo.name, methodName});
        }
        methodInfo.workflowDecl = new ClassInfo.MethodWorkflowDeclInfo(declKind);
      }
      return null;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      methodLineNumber = line;
    }

    @Override
    public void visitEnd() {
      // Pop any remaining suppressions
      if (suppressionStack != null && methodSuppressions > 0) {
        for (int i = 0; i < methodSuppressions; i++) {
          suppressionStack.pop();
        }
        // Also warn if there were un-restored suppressions
        int expectedMethodSuppressions = methodSuppressionAnnotation ? 1 : 0;
        if (methodSuppressions > expectedMethodSuppressions) {
          logger.log(
              Level.WARNING,
              "{0} warning suppression(s) not restored in {1}.{2}",
              new Object[] {
                methodSuppressions - expectedMethodSuppressions, classInfo.name, methodName
              });
        }
      }
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      // If this method is already configured invalid one way or another, don't
      // be concerned with invalid calls
      if (methodInfo.configuredInvalid != null) {
        return;
      }

      // Check if the call is being suppressed
      if (maybeSuppressInsn(owner, name, descriptor)) {
        return;
      }

      // We tried many ways to do stream processing of invalid calls while they
      // are loaded. While the recursion issue is trivially solved, properly
      // resolving implemented interfaces (using proper specificity checks to
      // disambiguate default impls) and similar challenges made it clear that
      // it is worth the extra memory to capture _all_ calls up front and
      // post-process whether they're invalid. This makes all method signatures
      // available for resolution at invalid-check time.
      if (methodInfo.memberAccesses == null) {
        methodInfo.memberAccesses = new ArrayList<>();
      }
      methodInfo.memberAccesses.add(
          new ClassInfo.MethodInvalidMemberAccessInfo(
              owner,
              name,
              descriptor,
              methodLineNumber,
              ClassInfo.MethodInvalidMemberAccessInfo.Operation.METHOD_CALL));
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      // If this method is already configured invalid one way or another, don't
      // be concerned with invalid fields
      if (methodInfo.configuredInvalid != null) {
        return;
      }

      // Check if the field is being suppressed
      if (maybeSuppressInsn(owner, name, descriptor)) {
        return;
      }

      // Check if the field is configured invalid one way or another
      Boolean invalid = config.invalidMembers.check(owner, name, null);
      if (invalid != null) {
        if (invalid) {
          if (methodInfo.memberAccesses == null) {
            methodInfo.memberAccesses = new ArrayList<>();
          }
          methodInfo.memberAccesses.add(
              new ClassInfo.MethodInvalidMemberAccessInfo(
                  owner,
                  name,
                  descriptor,
                  methodLineNumber,
                  ClassInfo.MethodInvalidMemberAccessInfo.Operation.FIELD_CONFIGURED_INVALID));
        }
        return;
      }

      // Check if this is getting/putting a static field. We don't check
      // whether the field is final or not until post-processing.
      if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
        if (methodInfo.memberAccesses == null) {
          methodInfo.memberAccesses = new ArrayList<>();
        }
        methodInfo.memberAccesses.add(
            new ClassInfo.MethodInvalidMemberAccessInfo(
                owner,
                name,
                descriptor,
                methodLineNumber,
                opcode == Opcodes.GETSTATIC
                    ? ClassInfo.MethodInvalidMemberAccessInfo.Operation.FIELD_STATIC_GET
                    : ClassInfo.MethodInvalidMemberAccessInfo.Operation.FIELD_STATIC_PUT));
      }
    }

    // True if instruction should not be checked for invalidity
    private boolean maybeSuppressInsn(String owner, String name, String descriptor) {
      try {
        // Check if suppression call
        if ("io/temporal/workflowcheck/WorkflowCheck".equals(owner)) {
          if ("suppressWarnings".equals(name)) {
            String[] specificDescriptors = null;
            // If there's a string, it must be an LDC or we ignore
            if ("(Ljava/lang/String;)V".equals(descriptor)) {
              // TODO(cretz): Should we throw instead of warn if this is not a constant string?
              if (prevInsnLdcString == null) {
                logger.log(
                    Level.WARNING,
                    "WorkflowCheck.suppressWarnings call not using string literal at {0}.{1} ({2})",
                    new Object[] {classInfo.name, methodName, fileLoc()});
                return true;
              }
              specificDescriptors = new String[] {prevInsnLdcString};
            }
            if (suppressionStack == null) {
              suppressionStack = new SuppressionStack();
            }
            methodSuppressions++;
            suppressionStack.push(specificDescriptors);
            prevInsnLdcString = null;
            return true;
          } else if ("restoreWarnings".equals(name)) {
            if (suppressionStack != null && methodSuppressions > 0) {
              methodSuppressions--;
              suppressionStack.pop();
            } else {
              logger.log(
                  Level.WARNING,
                  "Restore with no previous suppression at {0}.{1} ({2})",
                  new Object[] {classInfo.name, methodName, fileLoc()});
            }
            return true;
          }
        }

        // If suppressed, don't go any further
        return suppressionStack != null
            && suppressionStack.checkSuppressed(owner, name, descriptor);
      } finally {
        prevInsnLdcString = null;
      }
    }

    private String fileLoc() {
      if (classInfo.fileName == null) {
        if (methodLineNumber == null) {
          return "<unknown file:line>";
        }
        return "<unknown file>:" + methodLineNumber;
      }
      return classInfo.fileName
          + ":"
          + (methodLineNumber == null ? "<unknown line>" : methodLineNumber);
    }

    @Override
    public void visitLdcInsn(Object value) {
      if (value instanceof String) {
        prevInsnLdcString = (String) value;
      } else {
        prevInsnLdcString = null;
      }
    }

    @Override
    public void visitInsn(int opcode) {
      prevInsnLdcString = null;
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
      prevInsnLdcString = null;
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
      prevInsnLdcString = null;
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      prevInsnLdcString = null;
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        Object... bootstrapMethodArguments) {
      prevInsnLdcString = null;
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      prevInsnLdcString = null;
    }

    @Override
    public void visitIincInsn(int varIndex, int increment) {
      prevInsnLdcString = null;
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
      prevInsnLdcString = null;
    }
  }
}
