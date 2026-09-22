package io.temporal.internal.payload.visitor.gen;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import io.temporal.internal.payload.visitor.gen.PayloadVisitorGenerator.FieldPlan;
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
 * Shared proto-descriptor model for the build-time generators: the message closure reachable from a
 * set of seed services, and which of those messages can transitively contain a {@code Payload}.
 */
final class ProtoClosure {

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
        FieldPlan plan = PayloadVisitorGenerator.classify(f);
        switch (plan.kind) {
          case SINGLE_PAYLOAD:
          case REPEATED_PAYLOAD:
          case PAYLOADS_SINGLE:
          case PAYLOADS_REPEATED:
          case MAP_PAYLOAD:
          case MAP_PAYLOADS:
          case ANY_SINGLE:
          case ANY_REPEATED:
          case MAP_ANY:
            direct = true;
            break;
          case MESSAGE_SINGLE:
          case MESSAGE_REPEATED:
          case MAP_MESSAGE:
            refs.add(plan.child);
            break;
          case IGNORE:
            break;
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
}
