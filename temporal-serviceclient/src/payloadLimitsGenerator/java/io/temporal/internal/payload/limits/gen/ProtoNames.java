package io.temporal.internal.payload.limits.gen;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Java naming rules for generated code, mirroring what {@code protoc} produces for a descriptor.
 *
 * <p>These duplicate the naming helpers in {@code temporal-sdk}'s {@code
 * io.temporal.internal.payload.visitor.gen.PayloadVisitorGenerator}; see {@link ProtoClosure} for
 * why the two generators are deliberately independent. Drift here is far less dangerous than drift
 * in reachability: a wrong accessor name produces generated code that does not compile.
 */
final class ProtoNames {
  private ProtoNames() {}

  /** Whether {@code d} is a Temporal-owned message (as opposed to a well-known/3rd-party type). */
  static boolean isTemporal(Descriptor d) {
    return d.getFullName().startsWith("temporal.");
  }

  /** Mirrors protoc's {@code UnderscoresToCamelCase}, used to derive Java accessor names. */
  static String camel(String input, boolean capNext) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c >= 'a' && c <= 'z') {
        sb.append(capNext ? Character.toUpperCase(c) : c);
        capNext = false;
      } else if (c >= 'A' && c <= 'Z') {
        if (i == 0 && !capNext) {
          sb.append(Character.toLowerCase(c));
        } else {
          sb.append(c);
        }
        capNext = false;
      } else if (c >= '0' && c <= '9') {
        sb.append(c);
        capNext = true;
      } else {
        capNext = true;
      }
    }
    return sb.toString();
  }

  /** Capitalized accessor base, e.g. {@code schedule_activity} -> {@code ScheduleActivity}. */
  static String base(FieldDescriptor f) {
    return camel(f.getName(), true);
  }

  private static String javaPackage(Descriptor d) {
    String pkg = d.getFile().getOptions().getJavaPackage();
    if (pkg == null || pkg.isEmpty()) {
      throw new IllegalStateException("message " + d.getFullName() + " has no java_package option");
    }
    return pkg;
  }

  /**
   * Source-form class name, e.g. {@code io.temporal.api.common.v1.Payload.ExternalPayloadDetails}.
   */
  static String sourceClassName(Descriptor d) {
    Deque<String> names = new ArrayDeque<>();
    for (Descriptor c = d; c != null; c = c.getContainingType()) {
      names.addFirst(c.getName());
    }
    return javaPackage(d) + "." + String.join(".", names);
  }

  /** Name of the generated per-message method for {@code full}, a descriptor full name. */
  static String methodName(String full) {
    return "visit_" + full.replace('.', '_');
  }
}
