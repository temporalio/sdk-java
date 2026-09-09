package io.temporal.internal.payload.limits.gen;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Build-time generator that emits {@code GeneratedPayloadLimitValidator}, the payload/memo
 * size-limit validator mirroring the Temporal server's size checks.
 *
 * <p>Starting from the request (RPC input) messages of {@code WorkflowService} and {@code
 * OperatorService}, it walks the proto closure, stopping at terminal payload/memo leaf types, and
 * emits one {@code visit_*} method per payload-bearing message plus an {@code instanceof}
 * dispatcher over the request roots. Each leaf is classified against the hand-authored {@code
 * *_FIELDS} tables below; the per-field measurement is derived mechanically from the proto shape.
 *
 * <p>The tables are the compile-time forcing function: a payload/memo-bearing field that is missing
 * from every table (or a stale/duplicate table entry) <b>fails the build</b>, so a proto change
 * cannot land until it is explicitly classified here.
 *
 * <p>Descriptors are read from a protoc-emitted descriptor set file rather than from compiled proto
 * classes, so that this generator does not depend on the compiled output of the module it generates
 * into.
 *
 * <p>Usage: {@code PayloadLimitValidatorGenerator <descriptor-set-file> <output-source-root>}.
 */
public final class PayloadLimitValidatorGenerator {

  static final String PAYLOAD = "temporal.api.common.v1.Payload";
  static final String PAYLOADS = "temporal.api.common.v1.Payloads";
  static final String MEMO = "temporal.api.common.v1.Memo";
  static final String HEADER = "temporal.api.common.v1.Header";
  static final String SEARCH_ATTRIBUTES = "temporal.api.common.v1.SearchAttributes";
  static final String FAILURE = "temporal.api.failure.v1.Failure";

  static final String OUTPUT_PACKAGE = "io.temporal.internal.payload.limits";
  static final String OUTPUT_CLASS = "GeneratedPayloadLimitValidator";

  /**
   * Types the walk stops at: it emits a table-driven leaf check at the holding field rather than
   * descending into their inner payload fields. {@code Failure} is measured as a whole proto
   * because that is how the server size-checks it (e.g. FailWorkflowExecution).
   */
  static final Set<String> TERMINAL_LEAVES =
      new HashSet<>(Arrays.asList(PAYLOAD, PAYLOADS, MEMO, HEADER, SEARCH_ATTRIBUTES, FAILURE));

  /**
   * Field paths the server size-checks as a whole serialized sub-message even though the field is
   * not itself payload-bearing (so payload reachability never reaches them). Measured via whole-
   * message size, classified via the table like any other leaf; the owning message is forced into
   * the closure so parents recurse into it.
   */
  static final String[] EXTRA_WHOLE_MESSAGE_LEAVES = {
    // protocol Message body (google.protobuf.Any): the server blob-checks proto.Size(message.Body)
    // when processing update messages and fails the WFT on exceed.
    "temporal.api.protocol.v1.Message.body",
  };

  // ===========================================================================
  // Payload-limits decision tables — the source of truth for how the SDK mirrors the server's
  // payload/memo size checks. Fields are grouped by policy:
  //   BLOB_FIELDS / MEMO_FIELDS   blob / memo limit, warn + error
  //   BLOB_WARN_FIELDS            blob limit, warning only (enforceError = false)
  //   NOT_VALIDATED_FIELDS        the server enforces no replicable limit on the field
  // Roots are derived automatically from the seed services' RPC input messages, so a new RPC can't
  // be silently missed — its payload fields become unclassified and fail the build until added
  // here.
  // ===========================================================================

  static final String[] BLOB_FIELDS = {
    "temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes.result",
    "temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes.input",
    "temporal.api.command.v1.FailWorkflowExecutionCommandAttributes.failure", // whole Failure proto
    "temporal.api.command.v1.ModifyWorkflowPropertiesCommandAttributes.upserted_memo", // memo
    // data-sum
    "temporal.api.command.v1.RecordMarkerCommandAttributes.details", // map<string,Payloads> sum
    "temporal.api.command.v1.ScheduleActivityTaskCommandAttributes.input",
    "temporal.api.command.v1.ScheduleNexusOperationCommandAttributes.input",
    "temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes.input",
    "temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes.input",
    "temporal.api.command.v1.UpsertWorkflowSearchAttributesCommandAttributes.search_attributes", // indexed_fields data-sum
    "temporal.api.protocol.v1.Message.body", // whole Any body; see EXTRA_WHOLE_MESSAGE_LEAVES
    "temporal.api.query.v1.WorkflowQuery.query_args",
    "temporal.api.workflow.v1.NewWorkflowExecutionInfo.input",
    "temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest.details",
    "temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest.details",
    "temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest.details",
    "temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest.details",
    "temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest.result",
    "temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest.result",
    "temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest.input",
    "temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest.signal_input",
    "temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest.input",
    "temporal.api.workflowservice.v1.StartActivityExecutionRequest.input",
    "temporal.api.workflowservice.v1.StartNexusOperationExecutionRequest.input",
    "temporal.api.workflowservice.v1.StartWorkflowExecutionRequest.input",
  };

  static final String[] MEMO_FIELDS = {
    "temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes.memo",
    "temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes.memo",
    "temporal.api.workflow.v1.NewWorkflowExecutionInfo.memo",
    "temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest.memo",
    "temporal.api.workflowservice.v1.StartWorkflowExecutionRequest.memo",
  };

  // Warn-only: the SDK warns but never proactively fails the task (failure responses; query
  // results).
  static final String[] BLOB_WARN_FIELDS = {
    "temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest.failure",
    "temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest.last_heartbeat_details",
    "temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest.failure",
    "temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest.last_heartbeat_details",
    "temporal.api.workflowservice.v1.RespondNexusTaskFailedRequest.failure",
    "temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.failure",
    "temporal.api.query.v1.WorkflowQueryResult.answer",
    "temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.query_result",
  };

  static final String[] NOT_VALIDATED_FIELDS = {
    // Headers: server records a HeaderSize metric only.
    "temporal.api.batch.v1.BatchOperationSignal.header",
    "temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes.header",
    "temporal.api.command.v1.RecordMarkerCommandAttributes.header",
    "temporal.api.command.v1.ScheduleActivityTaskCommandAttributes.header",
    "temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes.header",
    "temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes.header",
    "temporal.api.query.v1.WorkflowQuery.header",
    "temporal.api.update.v1.Input.header",
    "temporal.api.workflow.v1.NewWorkflowExecutionInfo.header",
    "temporal.api.workflow.v1.PostResetOperation.SignalWorkflow.header",
    "temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest.header",
    "temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest.header",
    "temporal.api.workflowservice.v1.StartActivityExecutionRequest.header",
    "temporal.api.workflowservice.v1.StartWorkflowExecutionRequest.header",
    // Search attributes: separate non-replicable SA limit (server merges with existing SAs).
    "temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes.search_attributes",
    "temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes.search_attributes",
    "temporal.api.workflow.v1.NewWorkflowExecutionInfo.search_attributes",
    "temporal.api.workflowservice.v1.CreateScheduleRequest.search_attributes",
    "temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest.search_attributes",
    "temporal.api.workflowservice.v1.StartActivityExecutionRequest.search_attributes",
    "temporal.api.workflowservice.v1.StartNexusOperationExecutionRequest.search_attributes",
    "temporal.api.workflowservice.v1.StartWorkflowExecutionRequest.search_attributes",
    "temporal.api.workflowservice.v1.UpdateScheduleRequest.search_attributes",
    // Internal carry-over fields the SDK doesn't author / the server doesn't size-check here.
    "temporal.api.command.v1.CancelWorkflowExecutionCommandAttributes.details",
    "temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes.failure",
    "temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes.last_completion_result",
    "temporal.api.command.v1.RecordMarkerCommandAttributes.failure",
    "temporal.api.workflowservice.v1.StartWorkflowExecutionRequest.continued_failure",
    "temporal.api.workflowservice.v1.StartWorkflowExecutionRequest.last_completion_result",
    "temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest.details",
    // Dedicated, non-fetchable limits: UserMetadata (nexus-start only); Nexus EndpointSpec
    // description.
    "temporal.api.sdk.v1.UserMetadata.details",
    "temporal.api.sdk.v1.UserMetadata.summary",
    "temporal.api.nexus.v1.EndpointSpec.description",
    // Event group marker label: custom 400-byte server-side limit, not blob/memo.
    "temporal.api.sdk.v1.EventGroupMarker.Label.label",
    // Update input args: frontend records a metric only — enforced on delivery via Message.body.
    "temporal.api.update.v1.Input.args",
    // Query/nexus failures and the nexus sync response payload: not size-checked on these paths.
    "temporal.api.nexus.v1.StartOperationResponse.Sync.payload",
    "temporal.api.nexus.v1.StartOperationResponse.failure",
    "temporal.api.query.v1.WorkflowQueryResult.failure",
    "temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.failure",
    // Schedules: server sums memo + action.input vs blob — cross-field aggregate (deferred).
    "temporal.api.workflowservice.v1.CreateScheduleRequest.memo",
    "temporal.api.workflowservice.v1.UpdateScheduleRequest.memo",
    // Enforced downstream: signal input is blob-checked per target on batch/reset fan-out.
    "temporal.api.batch.v1.BatchOperationSignal.input",
    "temporal.api.workflow.v1.PostResetOperation.SignalWorkflow.input",
    // Not size-checked by the server.
    "temporal.api.batch.v1.BatchOperationTermination.details",
    "temporal.api.deployment.v1.UpdateDeploymentMetadata.upsert_entries",
    "temporal.api.workflowservice.v1.UpdateWorkerDeploymentVersionMetadataRequest.upsert_entries",
    // Cloud compute API; not size-checked by the OSS server.
    "temporal.api.compute.v1.ComputeProvider.details",
    "temporal.api.compute.v1.ComputeScaler.details",
  };

  // --- Policy / target model -------------------------------------------------

  enum LimitClass {
    BLOB,
    MEMO;

    String token() {
      return this == BLOB ? "LimitClass.BLOB" : "LimitClass.MEMO";
    }
  }

  /**
   * Classification of a leaf field, or {@link #NOT_VALIDATED} for a field the server doesn't check.
   */
  static final class FieldPolicy {
    static final FieldPolicy NOT_VALIDATED = new FieldPolicy(null, false, false);

    final boolean validated;
    final LimitClass limitClass;
    final boolean enforceError;

    private FieldPolicy(LimitClass limitClass, boolean enforceError, boolean validated) {
      this.limitClass = limitClass;
      this.enforceError = enforceError;
      this.validated = validated;
    }

    static FieldPolicy validated(LimitClass limitClass, boolean enforceError) {
      return new FieldPolicy(limitClass, enforceError, true);
    }
  }

  /** How a leaf field's size is measured; derived mechanically from the proto shape. */
  enum LeafKind {
    SINGLE_PAYLOADS,
    SINGLE_PAYLOAD,
    REPEATED_PAYLOAD,
    SINGLE_MEMO,
    /** A Memo validated against the blob limit: measured as the data-sum of its fields. */
    MEMO_FIELDS_DATA_SUM,
    SINGLE_HEADER,
    SINGLE_SEARCH_ATTRIBUTES,
    MAP_PAYLOAD,
    MAP_PAYLOADS,
    /** A whole message measured by its serialized proto size (e.g. Failure). */
    WHOLE_MESSAGE,
    /** A repeated whole message, summed. */
    REPEATED_WHOLE_MESSAGE
  }

  enum StructShape {
    SINGLE,
    REPEATED,
    MAP
  }

  /** Where a field leads: a measured leaf, a recurse-into struct, or ignored. */
  static final class Target {
    static final Target SKIP = new Target(null, null, null);

    final LeafKind leaf; // non-null for a leaf
    final StructShape structShape; // non-null for a struct
    final Descriptor child; // struct child message descriptor

    private Target(LeafKind leaf, StructShape structShape, Descriptor child) {
      this.leaf = leaf;
      this.structShape = structShape;
      this.child = child;
    }

    static Target leaf(LeafKind kind) {
      return new Target(kind, null, null);
    }

    static Target struct(StructShape shape, Descriptor child) {
      return new Target(null, shape, child);
    }
  }

  // --- Entry point -----------------------------------------------------------

  /** Proto files whose services seed the walk. */
  static final String WORKFLOW_SERVICE_PROTO = "temporal/api/workflowservice/v1/service.proto";

  static final String OPERATOR_SERVICE_PROTO = "temporal/api/operatorservice/v1/service.proto";

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException(
          "usage: PayloadLimitValidatorGenerator <descriptor-set-file> <output-source-root>");
    }
    new PayloadLimitValidatorGenerator().run(Paths.get(args[0]), Paths.get(args[1]));
  }

  private ProtoClosure closure;
  private final Map<String, FieldPolicy> table = loadTable();
  private final Set<String> usedKeys = new HashSet<>();
  private final Set<String> unclassified = new TreeSet<>();
  private final Set<String> extraLeafOwners = new HashSet<>();

  /**
   * Full names of the messages that can produce at least one check; see {@link #validatingClosure}.
   */
  private Set<String> validating = new HashSet<>();

  void run(Path descriptorSetFile, Path outputRoot)
      throws IOException, DescriptorValidationException {
    Map<String, FileDescriptor> files = ProtoDescriptorSets.load(descriptorSetFile);
    List<FileDescriptor> seeds =
        Arrays.asList(
            ProtoDescriptorSets.require(files, WORKFLOW_SERVICE_PROTO),
            ProtoDescriptorSets.require(files, OPERATOR_SERVICE_PROTO));
    this.closure = ProtoClosure.of(seeds);

    for (String path : EXTRA_WHOLE_MESSAGE_LEAVES) {
      int dot = path.lastIndexOf('.');
      extraLeafOwners.add(path.substring(0, dot));
    }

    // Roots: the RPC input (request) message of every method of the seed services.
    Set<Descriptor> roots = new LinkedHashSet<>();
    for (FileDescriptor seed : seeds) {
      for (ServiceDescriptor service : seed.getServices()) {
        for (MethodDescriptor method : service.getMethods()) {
          roots.add(method.getInputType());
        }
      }
    }

    // Closure of payload-bearing messages reachable from the roots, keyed for deterministic output.
    Map<String, Descriptor> toGenerate = new TreeMap<>();
    for (Descriptor d : limitsClosure(roots)) {
      toGenerate.put(d.getFullName(), d);
    }

    // Classify every leaf in the closure before pruning below, so the table guards always see the
    // full set of payload-bearing fields regardless of what ends up being emitted.
    for (Descriptor d : toGenerate.values()) {
      for (Leaf leaf : leavesOf(d)) {
        leafPolicy(d.getFullName() + "." + leaf.field.getName());
      }
    }

    // Fail the build on any unclassified payload-bearing field.
    if (!unclassified.isEmpty()) {
      StringBuilder msg = new StringBuilder();
      msg.append("payload-limits: ")
          .append(unclassified.size())
          .append(
              " payload-bearing field(s) are not classified. Add each to the right *_FIELDS list "
                  + "(BLOB_FIELDS / MEMO_FIELDS / BLOB_WARN_FIELDS / NOT_VALIDATED_FIELDS) in "
                  + "PayloadLimitValidatorGenerator:\n");
      for (String p : unclassified) {
        msg.append("    \"").append(p).append("\",\n");
      }
      throw new IllegalStateException(msg.toString());
    }

    // Fail the build on stale table entries (no longer a payload-bearing field in the closure).
    Set<String> stale = new TreeSet<>(table.keySet());
    stale.removeAll(usedKeys);
    if (!stale.isEmpty()) {
      StringBuilder msg = new StringBuilder();
      msg.append("payload-limits: ")
          .append(stale.size())
          .append(
              " stale entr(y/ies) in the *_FIELDS tables (PayloadLimitValidatorGenerator) no "
                  + "longer correspond to a payload-bearing field; remove them:\n");
      for (String p : stale) {
        msg.append("    ").append(p).append("\n");
      }
      throw new IllegalStateException(msg.toString());
    }

    // Drop the messages that can never produce a check, and with them the fields that only lead
    // there. Classification above already happened, so pruning cannot weaken the build guards.
    this.validating = validatingClosure(toGenerate);
    toGenerate.keySet().retainAll(validating);

    String source = emit(toGenerate, roots);

    Path dir = outputRoot;
    for (String part : OUTPUT_PACKAGE.split("\\.", -1)) {
      dir = dir.resolve(part);
    }
    Files.createDirectories(dir);
    Path out = dir.resolve(OUTPUT_CLASS + ".java");
    Files.write(out, source.getBytes(StandardCharsets.UTF_8));
    System.out.println(
        "PayloadLimitValidatorGenerator: wrote " + toGenerate.size() + " validators to " + out);
  }

  // --- Table loading ---------------------------------------------------------

  private static Map<String, FieldPolicy> loadTable() {
    Map<String, FieldPolicy> map = new HashMap<>();
    putAll(map, BLOB_FIELDS, FieldPolicy.validated(LimitClass.BLOB, true));
    putAll(map, MEMO_FIELDS, FieldPolicy.validated(LimitClass.MEMO, true));
    putAll(map, BLOB_WARN_FIELDS, FieldPolicy.validated(LimitClass.BLOB, false));
    putAll(map, NOT_VALIDATED_FIELDS, FieldPolicy.NOT_VALIDATED);
    return map;
  }

  private static void putAll(Map<String, FieldPolicy> map, String[] paths, FieldPolicy policy) {
    for (String path : paths) {
      if (map.put(path, policy) != null) {
        throw new IllegalStateException("payload-limits: duplicate table entry for `" + path + "`");
      }
    }
  }

  // --- Reachability + classification -----------------------------------------

  /**
   * Whether {@code d} is part of the validated closure (payload-reachable or an extra-leaf owner).
   */
  private boolean included(Descriptor d) {
    return closure.reaches(d) || extraLeafOwners.contains(d.getFullName());
  }

  /**
   * BFS from the roots, following payload-containing structural fields; excludes terminal leaves.
   */
  private Set<Descriptor> limitsClosure(Set<Descriptor> roots) {
    Set<Descriptor> result = new LinkedHashSet<>();
    Set<String> seen = new HashSet<>();
    Deque<Descriptor> queue = new ArrayDeque<>(roots);
    while (!queue.isEmpty()) {
      Descriptor d = queue.poll();
      String fqn = d.getFullName();
      if (TERMINAL_LEAVES.contains(fqn) || !seen.add(fqn)) {
        continue;
      }
      if (!included(d)) {
        continue;
      }
      result.add(d);
      for (FieldDescriptor f : d.getFields()) {
        Target t = classify(f);
        if (t.structShape != null && included(t.child)) {
          queue.add(t.child);
        }
      }
    }
    return result;
  }

  static Target classify(FieldDescriptor f) {
    if (f.isMapField()) {
      FieldDescriptor value = f.getMessageType().findFieldByNumber(2);
      if (value.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
        return Target.SKIP;
      }
      String name = value.getMessageType().getFullName();
      if (PAYLOAD.equals(name)) {
        return Target.leaf(LeafKind.MAP_PAYLOAD);
      }
      if (PAYLOADS.equals(name)) {
        return Target.leaf(LeafKind.MAP_PAYLOADS);
      }
      if (ProtoNames.isTemporal(value.getMessageType())) {
        return Target.struct(StructShape.MAP, value.getMessageType());
      }
      return Target.SKIP;
    }
    if (f.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
      return Target.SKIP;
    }
    String name = f.getMessageType().getFullName();
    boolean repeated = f.isRepeated();
    LeafKind kind = terminalLeafKind(name, repeated);
    if (kind != null) {
      return Target.leaf(kind);
    }
    if (!ProtoNames.isTemporal(f.getMessageType())) {
      return Target.SKIP;
    }
    return Target.struct(repeated ? StructShape.REPEATED : StructShape.SINGLE, f.getMessageType());
  }

  /**
   * The leaf measurement kind for a terminal-leaf type, or {@code null} for a recurse-into message.
   */
  static LeafKind terminalLeafKind(String typeName, boolean repeated) {
    if (PAYLOAD.equals(typeName)) {
      return repeated ? LeafKind.REPEATED_PAYLOAD : LeafKind.SINGLE_PAYLOAD;
    }
    if (PAYLOADS.equals(typeName)) {
      return LeafKind.SINGLE_PAYLOADS;
    }
    if (MEMO.equals(typeName)) {
      return LeafKind.SINGLE_MEMO;
    }
    if (HEADER.equals(typeName)) {
      return LeafKind.SINGLE_HEADER;
    }
    if (SEARCH_ATTRIBUTES.equals(typeName)) {
      return LeafKind.SINGLE_SEARCH_ATTRIBUTES;
    }
    if (FAILURE.equals(typeName)) {
      return repeated ? LeafKind.REPEATED_WHOLE_MESSAGE : LeafKind.WHOLE_MESSAGE;
    }
    return null;
  }

  /** A Memo validated against the blob limit is measured as its fields' data-sum. */
  static LeafKind effectiveKind(LeafKind kind, LimitClass limitClass) {
    if (kind == LeafKind.SINGLE_MEMO && limitClass == LimitClass.BLOB) {
      return LeafKind.MEMO_FIELDS_DATA_SUM;
    }
    return kind;
  }

  private FieldPolicy leafPolicy(String protoPath) {
    FieldPolicy policy = table.get(protoPath);
    if (policy == null) {
      unclassified.add(protoPath);
      return null;
    }
    usedKeys.add(protoPath);
    return policy;
  }

  /** A measured leaf field of a message: the field itself, plus how its size is taken. */
  private static final class Leaf {
    final FieldDescriptor field;
    final LeafKind kind;

    Leaf(FieldDescriptor field, LeafKind kind) {
      this.field = field;
      this.kind = kind;
    }
  }

  /** Every measured leaf of {@code d}, in emission order: terminal leaves, then forced extras. */
  private List<Leaf> leavesOf(Descriptor d) {
    List<Leaf> leaves = new ArrayList<>();
    for (FieldDescriptor f : d.getFields()) {
      Target t = classify(f);
      if (t.leaf != null) {
        leaves.add(new Leaf(f, t.leaf));
      }
    }
    leaves.addAll(extraLeavesOf(d));
    return leaves;
  }

  /** The {@link #EXTRA_WHOLE_MESSAGE_LEAVES} entries owned by {@code d}. */
  private List<Leaf> extraLeavesOf(Descriptor d) {
    List<Leaf> leaves = new ArrayList<>();
    for (String path : EXTRA_WHOLE_MESSAGE_LEAVES) {
      int dot = path.lastIndexOf('.');
      if (!path.substring(0, dot).equals(d.getFullName())) {
        continue;
      }
      FieldDescriptor f = d.findFieldByName(path.substring(dot + 1));
      if (f != null) {
        leaves.add(new Leaf(f, LeafKind.WHOLE_MESSAGE));
      }
    }
    return leaves;
  }

  /** The struct-typed fields of {@code d} that the traversal recurses into. */
  private List<Target> structsOf(Descriptor d) {
    List<Target> structs = new ArrayList<>();
    for (FieldDescriptor f : d.getFields()) {
      Target t = classify(f);
      if (t.structShape != null && included(t.child)) {
        structs.add(t);
      }
    }
    return structs;
  }

  /**
   * Messages that can produce at least one check: those with a validated leaf, plus, to a fixpoint,
   * those that lead to one. Everything else traverses to nothing, so emitting it (and the fields
   * that reach it) would only cost code size and per-request work.
   */
  private Set<String> validatingClosure(Map<String, Descriptor> toGenerate) {
    Set<String> result = new HashSet<>();
    Map<String, List<String>> children = new HashMap<>();
    for (Descriptor d : toGenerate.values()) {
      for (Leaf leaf : leavesOf(d)) {
        FieldPolicy policy = table.get(d.getFullName() + "." + leaf.field.getName());
        if (policy != null && policy.validated) {
          result.add(d.getFullName());
          break;
        }
      }
      List<String> refs = new ArrayList<>();
      for (Target t : structsOf(d)) {
        refs.add(t.child.getFullName());
      }
      children.put(d.getFullName(), refs);
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (Map.Entry<String, List<String>> e : children.entrySet()) {
        if (result.contains(e.getKey())) {
          continue;
        }
        for (String child : e.getValue()) {
          if (result.contains(child)) {
            result.add(e.getKey());
            changed = true;
            break;
          }
        }
      }
    }
    return result;
  }

  // --- Emission --------------------------------------------------------------

  private String emit(Map<String, Descriptor> toGenerate, Set<Descriptor> roots) {
    StringBuilder sb = new StringBuilder();
    sb.append("// Code generated by PayloadLimitValidatorGenerator; DO NOT EDIT.\n");
    sb.append("package ").append(OUTPUT_PACKAGE).append(";\n\n");
    sb.append("import com.google.protobuf.Message;\n");
    sb.append("import java.util.HashMap;\n");
    sb.append("import java.util.List;\n");
    sb.append("import java.util.Map;\n");
    sb.append("import java.util.function.BiConsumer;\n\n");
    sb.append("@SuppressWarnings(\"deprecation\")\n");
    sb.append("final class ").append(OUTPUT_CLASS).append(" {\n");
    sb.append("  private ").append(OUTPUT_CLASS).append("() {}\n\n");

    // Dispatcher over the payload-bearing request roots.
    List<Descriptor> rootList = new ArrayList<>();
    Set<String> rootSeen = new HashSet<>();
    for (Descriptor r : roots) {
      if (toGenerate.containsKey(r.getFullName()) && rootSeen.add(r.getFullName())) {
        rootList.add(r);
      }
    }
    rootList.sort((a, b) -> a.getFullName().compareTo(b.getFullName()));
    // Keyed on the exact class: generated proto message classes are final, so there are no
    // subtypes to widen for, and a single hash lookup beats a linear chain of instanceof tests --
    // above all for the requests that carry no payloads, which is most of them.
    sb.append("  private static final Map<Class<?>, BiConsumer<PayloadLimitSink, Message>>")
        .append(" DISPATCH = new HashMap<>();\n\n");
    sb.append("  static {\n");
    for (Descriptor r : rootList) {
      String src = ProtoNames.sourceClassName(r);
      sb.append("    DISPATCH.put(")
          .append(src)
          .append(".class, (sink, msg) -> ")
          .append(ProtoNames.methodName(r.getFullName()))
          .append("(sink, (")
          .append(src)
          .append(") msg));\n");
    }
    sb.append("  }\n\n");
    sb.append("  static void dispatch(PayloadLimitSink sink, Message request) {\n");
    sb.append("    BiConsumer<PayloadLimitSink, Message> validator")
        .append(" = DISPATCH.get(request.getClass());\n");
    sb.append("    if (validator != null) {\n");
    sb.append("      validator.accept(sink, request);\n");
    sb.append("    }\n");
    sb.append("  }\n\n");

    for (Descriptor d : toGenerate.values()) {
      emitValidateMethod(sb, d);
    }

    sb.append("}\n");
    return sb.toString();
  }

  private void emitValidateMethod(StringBuilder sb, Descriptor d) {
    String src = ProtoNames.sourceClassName(d);
    sb.append("  static void ")
        .append(ProtoNames.methodName(d.getFullName()))
        .append("(PayloadLimitSink sink, ")
        .append(src)
        .append(" msg) {\n");
    int fi = 0;
    for (FieldDescriptor f : d.getFields()) {
      Target t = classify(f);
      if (t.leaf != null) {
        emitLeaf(sb, d.getFullName(), f, t.leaf);
      } else if (t.structShape != null
          && included(t.child)
          && validating.contains(t.child.getFullName())) {
        emitStruct(sb, f, t.structShape, t.child, fi++);
      }
    }
    // Extra whole-message leaves whose owner is this message.
    for (Leaf leaf : extraLeavesOf(d)) {
      emitLeaf(sb, d.getFullName(), leaf.field, leaf.kind);
    }
    sb.append("  }\n\n");
  }

  private void emitLeaf(StringBuilder sb, String ownerFqn, FieldDescriptor f, LeafKind kind) {
    String protoField = f.getName();
    FieldPolicy policy = leafPolicy(ownerFqn + "." + protoField);
    if (policy == null || !policy.validated) {
      return; // unclassified (build fails) or NotValidated (no check)
    }
    LeafKind effective = effectiveKind(kind, policy.limitClass);
    String base = ProtoNames.base(f);
    String classToken = policy.limitClass.token();
    String ee = String.valueOf(policy.enforceError);
    switch (effective) {
      case SINGLE_PAYLOADS:
      case SINGLE_PAYLOAD:
      case SINGLE_MEMO:
      case MEMO_FIELDS_DATA_SUM:
      case SINGLE_HEADER:
      case SINGLE_SEARCH_ATTRIBUTES:
      case WHOLE_MESSAGE:
        sb.append("    if (msg.has").append(base).append("()) {\n");
        sb.append("      sink.check(\"")
            .append(protoField)
            .append("\", ")
            .append(classToken)
            .append(", ")
            .append(singleSizeExpr(effective, "msg.get" + base + "()"))
            .append(", ")
            .append(ee)
            .append(");\n");
        sb.append("    }\n");
        return;
      case REPEATED_PAYLOAD:
      case REPEATED_WHOLE_MESSAGE:
        sb.append("    sink.check(\"")
            .append(protoField)
            .append("\", ")
            .append(classToken)
            .append(", PayloadLimitSizes.serializedSizeSum(msg.get")
            .append(base)
            .append("List()), ")
            .append(ee)
            .append(");\n");
        return;
      case MAP_PAYLOAD:
        sb.append("    sink.check(\"")
            .append(protoField)
            .append("\", ")
            .append(classToken)
            .append(", PayloadLimitSizes.mapPayloadDataSum(msg.get")
            .append(base)
            .append("Map()), ")
            .append(ee)
            .append(");\n");
        return;
      case MAP_PAYLOADS:
        sb.append("    sink.check(\"")
            .append(protoField)
            .append("\", ")
            .append(classToken)
            .append(", PayloadLimitSizes.mapPayloadsSum(msg.get")
            .append(base)
            .append("Map()), ")
            .append(ee)
            .append(");\n");
        return;
    }
    throw new AssertionError(effective);
  }

  /** Size expression for an optional-singular leaf, given the getter expression for the value. */
  private static String singleSizeExpr(LeafKind kind, String getter) {
    switch (kind) {
      case SINGLE_PAYLOADS:
      case SINGLE_PAYLOAD:
      case SINGLE_MEMO:
      case WHOLE_MESSAGE:
        return "PayloadLimitSizes.serializedSize(" + getter + ")";
      case MEMO_FIELDS_DATA_SUM:
      case SINGLE_HEADER:
        return "PayloadLimitSizes.mapPayloadDataSum(" + getter + ".getFieldsMap())";
      case SINGLE_SEARCH_ATTRIBUTES:
        return "PayloadLimitSizes.mapPayloadDataSum(" + getter + ".getIndexedFieldsMap())";
      case REPEATED_PAYLOAD:
      case REPEATED_WHOLE_MESSAGE:
      case MAP_PAYLOAD:
      case MAP_PAYLOADS:
        break;
    }
    throw new AssertionError(kind);
  }

  private void emitStruct(
      StringBuilder sb, FieldDescriptor f, StructShape shape, Descriptor child, int fi) {
    String protoField = f.getName();
    String base = ProtoNames.base(f);
    String childMethod = ProtoNames.methodName(child.getFullName());
    String childSrc = ProtoNames.sourceClassName(child);
    switch (shape) {
      case SINGLE:
        sb.append("    if (msg.has").append(base).append("()) {\n");
        sb.append("      sink.enter(\"").append(protoField).append("\");\n");
        sb.append("      ")
            .append(childMethod)
            .append("(sink, msg.get")
            .append(base)
            .append("());\n");
        sb.append("      sink.exit();\n");
        sb.append("    }\n");
        return;
      case REPEATED:
        sb.append("    {\n");
        sb.append("      List<")
            .append(childSrc)
            .append("> __l")
            .append(fi)
            .append(" = msg.get")
            .append(base)
            .append("List();\n");
        sb.append("      for (int __i")
            .append(fi)
            .append(" = 0; __i")
            .append(fi)
            .append(" < __l")
            .append(fi)
            .append(".size(); __i")
            .append(fi)
            .append("++) {\n");
        sb.append("        sink.enter(\"")
            .append(protoField)
            .append("\", __i")
            .append(fi)
            .append(");\n");
        sb.append("        ")
            .append(childMethod)
            .append("(sink, __l")
            .append(fi)
            .append(".get(__i")
            .append(fi)
            .append("));\n");
        sb.append("        sink.exit();\n");
        sb.append("      }\n");
        sb.append("    }\n");
        return;
      case MAP:
        sb.append("    for (Map.Entry<String, ")
            .append(childSrc)
            .append("> __e")
            .append(fi)
            .append(" : msg.get")
            .append(base)
            .append("Map().entrySet()) {\n");
        sb.append("      sink.enter(\"")
            .append(protoField)
            .append("\", __e")
            .append(fi)
            .append(".getKey());\n");
        sb.append("      ")
            .append(childMethod)
            .append("(sink, __e")
            .append(fi)
            .append(".getValue());\n");
        sb.append("      sink.exit();\n");
        sb.append("    }\n");
        return;
    }
    throw new AssertionError(shape);
  }
}
