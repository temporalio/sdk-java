package io.temporal.internal.payload.limits.gen;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The message closure reachable from a set of seed services, and which of those messages can
 * transitively contain a {@code Payload}.
 *
 * <p>Reachability is deliberately the same notion the payload visitor uses: a {@code
 * google.protobuf.Any} counts as payload-bearing, because its contents are opaque here and may hold
 * payloads.
 *
 * <p><b>A near-copy of this class exists in {@code temporal-sdk}</b>, as {@code
 * io.temporal.internal.payload.visitor.gen.ProtoClosure}, serving the payload visitor. See that
 * class for why the duplication is deliberate. The copies differ only in how each obtains its
 * {@code Descriptor}s (a descriptor set file here, compiled proto classes there) and in that this
 * one computes reachability without the visitor generator's {@code FieldPlan}/{@code classify}
 * model. <b>Payload reachability must stay identical in both</b>; verify any change by confirming
 * both generators' output is byte-identical before and after it.
 */
final class ProtoClosure {

  private static final String PAYLOAD = "temporal.api.common.v1.Payload";
  private static final String PAYLOADS = "temporal.api.common.v1.Payloads";
  private static final String ANY = "google.protobuf.Any";

  /** All non-map-entry messages in the closure, in discovery order. */
  final List<Descriptor> allMessages;

  /** Full names of the messages that can transitively contain a payload. */
  private final Set<String> reaches;

  private ProtoClosure(List<Descriptor> allMessages, Set<String> reaches) {
    this.allMessages = allMessages;
    this.reaches = reaches;
  }

  /** Whether {@code d} can transitively contain a payload. */
  boolean reaches(Descriptor d) {
    return reaches.contains(d.getFullName());
  }

  /** Builds the closure and payload-reachability set from the given seed file descriptors. */
  static ProtoClosure of(List<FileDescriptor> seeds) {
    List<Descriptor> all = collectMessages(fileClosure(seeds));
    return new ProtoClosure(all, computeReachability(all));
  }

  // --- Descriptor discovery ---

  private static Set<FileDescriptor> fileClosure(List<FileDescriptor> seeds) {
    Set<FileDescriptor> seen = new LinkedHashSet<>();
    Deque<FileDescriptor> queue = new ArrayDeque<>(seeds);
    while (!queue.isEmpty()) {
      FileDescriptor f = queue.poll();
      if (seen.add(f)) {
        queue.addAll(f.getDependencies());
      }
    }
    return seen;
  }

  private static List<Descriptor> collectMessages(Set<FileDescriptor> files) {
    List<Descriptor> result = new ArrayList<>();
    for (FileDescriptor f : files) {
      for (Descriptor d : f.getMessageTypes()) {
        collectMessages(d, result);
      }
    }
    return result;
  }

  private static void collectMessages(Descriptor d, List<Descriptor> out) {
    if (d.getOptions().getMapEntry()) {
      return; // synthetic map entry type; handled via the owning map field
    }
    out.add(d);
    for (Descriptor nested : d.getNestedTypes()) {
      collectMessages(nested, out);
    }
  }

  // --- Reachability ---

  /**
   * Least-fixpoint reachability over the message-reference graph. A message reaches a payload if it
   * has a direct payload/Any field, or it references (via a message or map-message field) another
   * message that does. Iterating to a fixpoint handles cycles (e.g. {@code Failure.cause})
   * correctly without over-approximating payload-free cycles.
   */
  private static Set<String> computeReachability(List<Descriptor> all) {
    Set<String> reaches = new HashSet<>();
    Map<String, List<Descriptor>> children = new HashMap<>();
    for (Descriptor d : all) {
      boolean direct = false;
      List<Descriptor> refs = new ArrayList<>();
      for (FieldDescriptor f : d.getFields()) {
        Descriptor referenced = referencedMessage(f);
        if (referenced == null) {
          if (carriesPayloadDirectly(f)) {
            direct = true;
          }
        } else {
          refs.add(referenced);
        }
      }
      if (direct) {
        reaches.add(d.getFullName());
      }
      children.put(d.getFullName(), refs);
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (Descriptor d : all) {
        if (reaches.contains(d.getFullName())) {
          continue;
        }
        for (Descriptor c : children.get(d.getFullName())) {
          if (reaches.contains(c.getFullName())) {
            reaches.add(d.getFullName());
            changed = true;
            break;
          }
        }
      }
    }
    return reaches;
  }

  /** Whether {@code f} holds payload data itself, in singular, repeated or map-valued form. */
  private static boolean carriesPayloadDirectly(FieldDescriptor f) {
    String name = valueMessageName(f);
    return PAYLOAD.equals(name) || PAYLOADS.equals(name) || ANY.equals(name);
  }

  /**
   * The Temporal message {@code f} refers to and should be recursed into, or {@code null} if it
   * carries payload data directly or leads nowhere interesting.
   */
  private static Descriptor referencedMessage(FieldDescriptor f) {
    if (carriesPayloadDirectly(f)) {
      return null;
    }
    Descriptor value = valueMessage(f);
    if (value == null || !ProtoNames.isTemporal(value)) {
      return null;
    }
    return value;
  }

  /** The message type a field holds, unwrapping map values; {@code null} for non-message fields. */
  private static Descriptor valueMessage(FieldDescriptor f) {
    if (f.isMapField()) {
      FieldDescriptor value = f.getMessageType().findFieldByNumber(2);
      return value.getJavaType() == FieldDescriptor.JavaType.MESSAGE
          ? value.getMessageType()
          : null;
    }
    return f.getJavaType() == FieldDescriptor.JavaType.MESSAGE ? f.getMessageType() : null;
  }

  private static String valueMessageName(FieldDescriptor f) {
    Descriptor value = valueMessage(f);
    return value == null ? null : value.getFullName();
  }
}
