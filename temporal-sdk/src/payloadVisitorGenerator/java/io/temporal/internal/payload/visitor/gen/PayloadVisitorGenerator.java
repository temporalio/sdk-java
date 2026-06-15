package io.temporal.internal.payload.visitor.gen;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Build-time generator that emits {@code GeneratedPayloadVisitor}.
 *
 * <p>Starting from the WorkflowService and OperatorService file descriptors, it walks the proto
 * closure, determines which message types can transitively contain a {@code Payload} (or a {@code
 * google.protobuf.Any}, treated conservatively as payload-bearing), and emits one {@code visit_*}
 * method per such type plus a registry keyed by descriptor full name.
 *
 * <p>Usage: {@code PayloadVisitorGenerator <output-source-root>}.
 */
public final class PayloadVisitorGenerator {

  static final String PAYLOAD = "temporal.api.common.v1.Payload";
  static final String PAYLOADS = "temporal.api.common.v1.Payloads";
  static final String ANY = "google.protobuf.Any";
  static final String SEARCH_ATTRIBUTES = "temporal.api.common.v1.SearchAttributes";
  static final String HEADER = "temporal.api.common.v1.Header";

  static final String OUTPUT_PACKAGE = "io.temporal.internal.payload.visitor";
  static final String OUTPUT_CLASS = "GeneratedPayloadVisitor";
  static final String PAYLOADS_FQN = "io.temporal.api.common.v1.Payloads";
  static final int REGISTER_CHUNK = 40;

  enum Kind {
    SINGLE_PAYLOAD,
    REPEATED_PAYLOAD,
    PAYLOADS_SINGLE,
    PAYLOADS_REPEATED,
    MAP_PAYLOAD,
    MAP_PAYLOADS,
    ANY_SINGLE,
    ANY_REPEATED,
    MAP_ANY,
    MESSAGE_SINGLE,
    MESSAGE_REPEATED,
    MAP_MESSAGE,
    IGNORE
  }

  /** Classification of a field: how it should be traversed, and its child message type if any. */
  static final class FieldPlan {
    final Kind kind;
    final Descriptor child; // child message descriptor for MESSAGE_* / MAP_MESSAGE, else null

    FieldPlan(Kind kind, Descriptor child) {
      this.kind = kind;
      this.child = child;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      throw new IllegalArgumentException("usage: PayloadVisitorGenerator <output-source-root>");
    }
    new PayloadVisitorGenerator().run(Paths.get(args[0]));
  }

  private ProtoClosure closure;

  void run(Path outputRoot) throws IOException {
    List<FileDescriptor> seeds =
        Arrays.asList(
            io.temporal.api.workflowservice.v1.ServiceProto.getDescriptor(),
            io.temporal.api.operatorservice.v1.ServiceProto.getDescriptor());

    this.closure = ProtoClosure.of(seeds);

    // Deterministic output order, keyed by descriptor full name.
    Map<String, Descriptor> emitted = new TreeMap<>();
    for (Descriptor d : closure.allMessages) {
      if (reaches(d)) {
        emitted.put(d.getFullName(), d);
      }
    }

    verifyAccessors(emitted.values());

    String source = emit(emitted);

    Path dir = outputRoot;
    for (String part : OUTPUT_PACKAGE.split("\\.", -1)) {
      dir = dir.resolve(part);
    }
    Files.createDirectories(dir);
    Path out = dir.resolve(OUTPUT_CLASS + ".java");
    Files.write(out, source.getBytes(StandardCharsets.UTF_8));
    System.out.println("PayloadVisitorGenerator: wrote " + emitted.size() + " visitors to " + out);
  }

  // --- Reachability + classification ---

  /** Whether {@code d} can transitively contain a payload; delegates to the shared closure. */
  private boolean reaches(Descriptor d) {
    return closure.reaches(d);
  }

  static FieldPlan classify(FieldDescriptor f) {
    if (f.isMapField()) {
      FieldDescriptor value = f.getMessageType().findFieldByNumber(2);
      if (value.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
        String name = value.getMessageType().getFullName();
        if (PAYLOAD.equals(name)) {
          return new FieldPlan(Kind.MAP_PAYLOAD, null);
        }
        if (PAYLOADS.equals(name)) {
          return new FieldPlan(Kind.MAP_PAYLOADS, null);
        }
        if (ANY.equals(name)) {
          return new FieldPlan(Kind.MAP_ANY, null);
        }
        if (isTemporal(value.getMessageType())) {
          return new FieldPlan(Kind.MAP_MESSAGE, value.getMessageType());
        }
        return new FieldPlan(Kind.IGNORE, null);
      }
      return new FieldPlan(Kind.IGNORE, null);
    }
    if (f.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
      return new FieldPlan(Kind.IGNORE, null);
    }
    String name = f.getMessageType().getFullName();
    boolean repeated = f.isRepeated();
    if (PAYLOAD.equals(name)) {
      return new FieldPlan(repeated ? Kind.REPEATED_PAYLOAD : Kind.SINGLE_PAYLOAD, null);
    }
    if (PAYLOADS.equals(name)) {
      return new FieldPlan(repeated ? Kind.PAYLOADS_REPEATED : Kind.PAYLOADS_SINGLE, null);
    }
    if (ANY.equals(name)) {
      return new FieldPlan(repeated ? Kind.ANY_REPEATED : Kind.ANY_SINGLE, null);
    }
    if (!isTemporal(f.getMessageType())) {
      // Non-Temporal messages (google well-known types, etc.) never carry Temporal payloads
      // except inside an Any, which is handled separately.
      return new FieldPlan(Kind.IGNORE, null);
    }
    return new FieldPlan(
        repeated ? Kind.MESSAGE_REPEATED : Kind.MESSAGE_SINGLE, f.getMessageType());
  }

  static boolean isTemporal(Descriptor d) {
    return d.getFullName().startsWith("temporal.");
  }

  // --- Java naming ---

  /** Mirrors protoc's UnderscoresToCamelCase used to derive Java accessor names. */
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

  static String javaPackage(Descriptor d) {
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

  /** Binary class name (nested types joined with {@code $}) for reflective verification. */
  static String binaryClassName(Descriptor d) {
    Deque<String> names = new ArrayDeque<>();
    for (Descriptor c = d; c != null; c = c.getContainingType()) {
      names.addFirst(c.getName());
    }
    return javaPackage(d) + "." + String.join("$", names);
  }

  static String methodName(String full) {
    return "visit_" + full.replace('.', '_');
  }

  // --- Accessor verification (build-time safety net for the naming rules) ---

  private void verifyAccessors(Iterable<Descriptor> descriptors) {
    for (Descriptor d : descriptors) {
      Class<?> builder;
      try {
        builder = Class.forName(binaryClassName(d) + "$Builder");
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("no builder class for " + d.getFullName(), e);
      }
      Set<String> methods = new HashSet<>();
      for (java.lang.reflect.Method m : builder.getMethods()) {
        methods.add(m.getName());
      }
      for (FieldDescriptor f : d.getFields()) {
        for (String required : requiredMethods(classify(f).kind, base(f))) {
          if (!methods.contains(required)) {
            throw new IllegalStateException(
                "expected builder method "
                    + builder.getName()
                    + "#"
                    + required
                    + " for field "
                    + d.getFullName()
                    + "."
                    + f.getName()
                    + " ("
                    + classify(f).kind
                    + ")");
          }
        }
      }
    }
  }

  static List<String> requiredMethods(Kind kind, String base) {
    switch (kind) {
      case SINGLE_PAYLOAD:
        return Arrays.asList("has" + base, "get" + base, "set" + base);
      case REPEATED_PAYLOAD:
        return Arrays.asList("get" + base + "List", "clear" + base, "addAll" + base);
      case PAYLOADS_SINGLE:
        return Arrays.asList("has" + base, "get" + base, "set" + base);
      case PAYLOADS_REPEATED:
        return Arrays.asList("get" + base, "get" + base + "Count", "set" + base);
      case MAP_PAYLOAD:
        return Arrays.asList("get" + base + "Map", "put" + base);
      case MAP_PAYLOADS:
      case MAP_ANY:
      case MAP_MESSAGE:
        return Arrays.asList("get" + base + "Map", "put" + base);
      case ANY_SINGLE:
      case MESSAGE_SINGLE:
        return Arrays.asList("has" + base, "get" + base + "Builder");
      case ANY_REPEATED:
      case MESSAGE_REPEATED:
        return Arrays.asList("get" + base + "BuilderList");
      default:
        return Arrays.asList();
    }
  }

  // --- Emission ---

  private String emit(Map<String, Descriptor> emitted) {
    StringBuilder sb = new StringBuilder();
    sb.append("// Code generated by PayloadVisitorGenerator; DO NOT EDIT.\n");
    sb.append("package ").append(OUTPUT_PACKAGE).append(";\n\n");
    sb.append("import java.util.ArrayList;\n");
    sb.append("import java.util.HashMap;\n");
    sb.append("import java.util.Map;\n\n");
    sb.append("@SuppressWarnings(\"deprecation\")\n");
    sb.append("final class ").append(OUTPUT_CLASS).append(" {\n");
    sb.append("  private ").append(OUTPUT_CLASS).append("() {}\n\n");

    List<Descriptor> list = new ArrayList<>(emitted.values());

    sb.append("  static final Map<String, MessageRegistryEntry> REGISTRY = buildRegistry();\n\n");
    sb.append("  private static Map<String, MessageRegistryEntry> buildRegistry() {\n");
    sb.append("    Map<String, MessageRegistryEntry> m = new HashMap<>(")
        .append(Math.max(16, list.size() * 2))
        .append(");\n");
    int chunks = (list.size() + REGISTER_CHUNK - 1) / REGISTER_CHUNK;
    for (int i = 0; i < chunks; i++) {
      sb.append("    register").append(i).append("(m);\n");
    }
    sb.append("    return m;\n");
    sb.append("  }\n\n");

    for (int i = 0; i < chunks; i++) {
      sb.append("  private static void register")
          .append(i)
          .append("(Map<String, MessageRegistryEntry> m) {\n");
      int start = i * REGISTER_CHUNK;
      int end = Math.min(start + REGISTER_CHUNK, list.size());
      for (int j = start; j < end; j++) {
        Descriptor d = list.get(j);
        String src = sourceClassName(d);
        String mn = methodName(d.getFullName());
        sb.append("    m.put(\"")
            .append(d.getFullName())
            .append("\", new MessageRegistryEntry((t, b) -> ")
            .append(mn)
            .append("(t, (")
            .append(src)
            .append(".Builder) b), ")
            .append(src)
            .append("::newBuilder));\n");
      }
      sb.append("  }\n\n");
    }

    for (Descriptor d : list) {
      emitVisitMethod(sb, d);
    }

    sb.append("}\n");
    return sb.toString();
  }

  private void emitVisitMethod(StringBuilder sb, Descriptor d) {
    String src = sourceClassName(d);
    sb.append("  static void ")
        .append(methodName(d.getFullName()))
        .append("(Traversal t, ")
        .append(src)
        .append(".Builder b) {\n");
    sb.append("    Object __c = t.enter(b);\n");
    int fi = 0;
    for (FieldDescriptor f : d.getFields()) {
      FieldPlan plan = classify(f);
      if (plan.kind == Kind.IGNORE) {
        continue;
      }
      if ((plan.kind == Kind.MESSAGE_SINGLE
              || plan.kind == Kind.MESSAGE_REPEATED
              || plan.kind == Kind.MAP_MESSAGE)
          && !reaches(plan.child)) {
        continue;
      }
      emitField(sb, f, plan, fi++);
    }
    sb.append("    t.exit(__c);\n");
    sb.append("  }\n\n");
  }

  private void emitField(StringBuilder sb, FieldDescriptor f, FieldPlan plan, int fi) {
    String B = base(f);
    String k = "__key" + fi;
    String v = "__v" + fi;
    switch (plan.kind) {
      case SINGLE_PAYLOAD:
        sb.append("    if (b.has").append(B).append("()) {\n");
        sb.append("      t.singlePayload(b.get")
            .append(B)
            .append("(), p -> b.set")
            .append(B)
            .append("(p));\n");
        sb.append("    }\n");
        break;
      case REPEATED_PAYLOAD:
        sb.append("    t.payloads(b.get").append(B).append("List(), pl -> {\n");
        sb.append("      b.clear").append(B).append("();\n");
        sb.append("      b.addAll").append(B).append("(pl);\n");
        sb.append("    });\n");
        break;
      case PAYLOADS_SINGLE:
        sb.append("    if (b.has").append(B).append("()) {\n");
        sb.append("      t.payloads(b.get").append(B).append("().getPayloadsList(),\n");
        sb.append("          pl -> b.set")
            .append(B)
            .append("(")
            .append(PAYLOADS_FQN)
            .append(".newBuilder().addAllPayloads(pl).build()));\n");
        sb.append("    }\n");
        break;
      case PAYLOADS_REPEATED:
        sb.append("    for (int ")
            .append(v)
            .append(" = 0; ")
            .append(v)
            .append(" < b.get")
            .append(B)
            .append("Count(); ")
            .append(v)
            .append("++) {\n");
        sb.append("      final int ").append(k).append(" = ").append(v).append(";\n");
        sb.append("      t.payloads(b.get")
            .append(B)
            .append("(")
            .append(k)
            .append(").getPayloadsList(),\n");
        sb.append("          pl -> b.set")
            .append(B)
            .append("(")
            .append(k)
            .append(", ")
            .append(PAYLOADS_FQN)
            .append(".newBuilder().addAllPayloads(pl).build()));\n");
        sb.append("    }\n");
        break;
      case MAP_PAYLOAD:
        sb.append("    for (String ")
            .append(k)
            .append(" : new ArrayList<>(b.get")
            .append(B)
            .append("Map().keySet())) {\n");
        sb.append("      final String ").append(v).append(" = ").append(k).append(";\n");
        sb.append("      t.singlePayload(b.get")
            .append(B)
            .append("Map().get(")
            .append(v)
            .append("), p -> b.put")
            .append(B)
            .append("(")
            .append(v)
            .append(", p));\n");
        sb.append("    }\n");
        break;
      case MAP_PAYLOADS:
        sb.append("    for (String ")
            .append(k)
            .append(" : new ArrayList<>(b.get")
            .append(B)
            .append("Map().keySet())) {\n");
        sb.append("      final String ").append(v).append(" = ").append(k).append(";\n");
        sb.append("      t.payloads(b.get")
            .append(B)
            .append("Map().get(")
            .append(v)
            .append(").getPayloadsList(),\n");
        sb.append("          pl -> b.put")
            .append(B)
            .append("(")
            .append(v)
            .append(", ")
            .append(PAYLOADS_FQN)
            .append(".newBuilder().addAllPayloads(pl).build()));\n");
        sb.append("    }\n");
        break;
      case ANY_SINGLE:
        sb.append("    if (b.has").append(B).append("()) {\n");
        sb.append("      t.any(b.get").append(B).append("Builder());\n");
        sb.append("    }\n");
        break;
      case ANY_REPEATED:
        sb.append("    for (com.google.protobuf.Any.Builder ")
            .append(v)
            .append(" : b.get")
            .append(B)
            .append("BuilderList()) {\n");
        sb.append("      t.any(").append(v).append(");\n");
        sb.append("    }\n");
        break;
      case MAP_ANY:
        sb.append("    for (String ")
            .append(k)
            .append(" : new ArrayList<>(b.get")
            .append(B)
            .append("Map().keySet())) {\n");
        sb.append("      final String ").append(v).append(" = ").append(k).append(";\n");
        sb.append("      com.google.protobuf.Any.Builder ab")
            .append(fi)
            .append(" = b.get")
            .append(B)
            .append("Map().get(")
            .append(v)
            .append(").toBuilder();\n");
        sb.append("      t.any(ab").append(fi).append(");\n");
        sb.append("      t.deferWriteBack(() -> b.put")
            .append(B)
            .append("(")
            .append(v)
            .append(", ab")
            .append(fi)
            .append(".build()));\n");
        sb.append("    }\n");
        break;
      case MESSAGE_SINGLE:
        {
          String guard = childGuard(plan.child);
          sb.append("    if (").append(guard).append("b.has").append(B).append("()) {\n");
          sb.append("      ")
              .append(methodName(plan.child.getFullName()))
              .append("(t, b.get")
              .append(B)
              .append("Builder());\n");
          sb.append("    }\n");
        }
        break;
      case MESSAGE_REPEATED:
        {
          String childSrc = sourceClassName(plan.child);
          String guard = childGuard(plan.child);
          if (!guard.isEmpty()) {
            sb.append("    if (").append(guard.substring(0, guard.length() - 4)).append(") {\n  ");
          }
          sb.append("    for (")
              .append(childSrc)
              .append(".Builder ")
              .append(v)
              .append(" : b.get")
              .append(B)
              .append("BuilderList()) {\n");
          sb.append("      ")
              .append(methodName(plan.child.getFullName()))
              .append("(t, ")
              .append(v)
              .append(");\n");
          sb.append("    }\n");
          if (!guard.isEmpty()) {
            sb.append("    }\n");
          }
        }
        break;
      case MAP_MESSAGE:
        {
          String childSrc = sourceClassName(plan.child);
          sb.append("    for (String ")
              .append(k)
              .append(" : new ArrayList<>(b.get")
              .append(B)
              .append("Map().keySet())) {\n");
          sb.append("      final String ").append(v).append(" = ").append(k).append(";\n");
          sb.append("      ")
              .append(childSrc)
              .append(".Builder vb")
              .append(fi)
              .append(" = b.get")
              .append(B)
              .append("Map().get(")
              .append(v)
              .append(").toBuilder();\n");
          sb.append("      ")
              .append(methodName(plan.child.getFullName()))
              .append("(t, vb")
              .append(fi)
              .append(");\n");
          sb.append("      t.deferWriteBack(() -> b.put")
              .append(B)
              .append("(")
              .append(v)
              .append(", vb")
              .append(fi)
              .append(".build()));\n");
          sb.append("    }\n");
        }
        break;
      default:
        break;
    }
  }

  /** Optional {@code &&}-terminated guard expression for SearchAttributes/Header skipping. */
  private String childGuard(Descriptor child) {
    String name = child.getFullName();
    if (SEARCH_ATTRIBUTES.equals(name)) {
      return "!t.skipSearchAttributes && ";
    }
    if (HEADER.equals(name)) {
      return "!t.skipHeaders && ";
    }
    return "";
  }
}
