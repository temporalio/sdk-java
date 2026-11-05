package io.temporal.internal.common;

import static io.temporal.internal.common.ProtoEnumNameUtils.EVENT_TYPE_PREFIX;
import static io.temporal.internal.common.ProtoEnumNameUtils.simplifiedToUniqueName;
import static io.temporal.internal.common.ProtoEnumNameUtils.uniqueToSimplifiedName;

import com.google.common.collect.ImmutableBiMap;
import io.temporal.api.common.v1.Execution;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.ExecutionType;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts between {@link Link} (used on history events and RPCs) and {@link
 * io.temporal.api.nexus.v1.Link} (the Nexus wire form: a URL plus a type string).
 *
 * <p>The following link types are supported, each with a fixed URL shape:
 *
 * <pre>
 *   WorkflowEvent    temporal:///namespaces/{ns}/workflows/{workflowId}/{runId}/history
 *   Workflow         temporal:///namespaces/{ns}/workflows/{workflowId}/{runId}
 *   NexusOperation   temporal:///namespaces/{ns}/nexus-operations/{operationId}/{runId}/details
 *   Activity         temporal:///namespaces/{ns}/activities/{activityId}/{runId}/details
 *   Callback         temporal:///namespaces/{ns}/{executionType}/{businessId}/{runId}/callbacks/{requestId}
 * </pre>
 *
 * <p>Whoever decodes one of these URLs — the receiving server, or this class when a link arrives
 * here — applies standard URL semantics: path segments are percent-decoded and query values are
 * form-decoded. The two rules differ on {@code +}, so the codecs here are deliberately asymmetric —
 * see {@link #encodePathSegment} and {@link #decodeQuery}.
 *
 * <p>Every method returns {@code null} rather than throwing when a link is malformed. Links are
 * decorative metadata attached to a Nexus call, so a bad one must never fail the call carrying it.
 */
public class LinkConverter {

  private static final Logger log = LoggerFactory.getLogger(LinkConverter.class);

  private static final String SCHEME = "temporal";
  private static final String UTF_8 = StandardCharsets.UTF_8.name();
  private static final String NAMESPACES_SEGMENT = "namespaces";

  private static final String REFERENCE_TYPE_KEY = "referenceType";
  private static final String EVENT_ID_KEY = "eventID";
  private static final String EVENT_TYPE_KEY = "eventType";
  private static final String REQUEST_ID_KEY = "requestID";
  private static final String REASON_KEY = "reason";
  private static final String COMPONENT_PATH_KEY = "componentPath";

  /**
   * The path keyword for each execution type a callback can be attached to, in both directions:
   * encoding looks up the keyword, decoding validates it and maps it back. Matches the keywords of
   * the other link types, so every link addresses an execution the same way.
   */
  private static final ImmutableBiMap<ExecutionType, String> EXECUTION_TYPE_SEGMENTS =
      ImmutableBiMap.of(
          ExecutionType.EXECUTION_TYPE_WORKFLOW, "workflows",
          ExecutionType.EXECUTION_TYPE_ACTIVITY, "activities",
          ExecutionType.EXECUTION_TYPE_NEXUS_OPERATION, "nexus-operations");

  private static final String EVENT_REFERENCE_TYPE =
      Link.WorkflowEvent.EventReference.getDescriptor().getName();
  private static final String REQUEST_ID_REFERENCE_TYPE =
      Link.WorkflowEvent.RequestIdReference.getDescriptor().getName();

  /**
   * The link types, as a table of (path keyword, path tail, tail ID, proto type name).
   *
   * <p>The path is always {@code /namespaces/{ns}/{keyword}/{id}/{runId}[/{tail}[/{tailId}]]}, so a
   * link has 5 segments with no tail, 6 with a tail, and 7 with a tail ID. Matching the count
   * exactly is what keeps a Workflow link distinguishable from a WorkflowEvent link, since they
   * share a keyword.
   */
  private enum LinkType {
    WORKFLOW_EVENT("workflows", "history", false, Link.WorkflowEvent.getDescriptor().getFullName()),

    /** A workflow execution as a whole, for when there is no history event to point at. */
    WORKFLOW("workflows", null, false, Link.Workflow.getDescriptor().getFullName()),

    NEXUS_OPERATION(
        "nexus-operations", "details", false, Link.NexusOperation.getDescriptor().getFullName()),

    ACTIVITY("activities", "details", false, Link.Activity.getDescriptor().getFullName()),

    /**
     * A callback attached to an execution. The keyword names the execution's type, so it varies and
     * is any value of {@link #EXECUTION_TYPE_SEGMENTS}. The tail ID is the callback's request ID.
     */
    CALLBACK(null, "callbacks", true, Link.Callback.getDescriptor().getFullName());

    @Nullable private final String keyword;
    @Nullable private final String tail;
    private final boolean hasTailId;
    private final String type;

    LinkType(@Nullable String keyword, @Nullable String tail, boolean hasTailId, String type) {
      this.keyword = keyword;
      this.tail = tail;
      this.hasTailId = hasTailId;
      this.type = type;
    }

    int segmentCount() {
      return 5 + (tail == null ? 0 : 1) + (hasTailId ? 1 : 0);
    }
  }

  // ===============================================================================================
  // Encode: Link -> nexus.v1.Link.
  // ===============================================================================================

  /** Dispatches on the oneof variant of {@code commonLink}. Returns null if no variant is set. */
  @Nullable
  public static io.temporal.api.nexus.v1.Link linkToNexusLink(Link commonLink) {
    if (commonLink.hasWorkflowEvent()) {
      return workflowEventToNexusLink(commonLink.getWorkflowEvent());
    }
    if (commonLink.hasNexusOperation()) {
      return nexusOperationToNexusLink(commonLink.getNexusOperation());
    }
    if (commonLink.hasWorkflow()) {
      return workflowLinkToNexusLink(commonLink.getWorkflow());
    }
    if (commonLink.hasActivity()) {
      return activityToNexusLink(commonLink.getActivity());
    }
    if (commonLink.hasCallback()) {
      return callbackToNexusLink(commonLink.getCallback());
    }
    return null;
  }

  public static io.temporal.api.nexus.v1.Link workflowEventToNexusLink(Link.WorkflowEvent we) {
    List<Map.Entry<String, String>> reference;
    try {
      reference = encodeReference(we);
    } catch (Exception e) {
      // Guarded separately because this runs before encode() is entered, so encode()'s own catch
      // does not cover it. encodeEventType rejects an event type these protos do not know
      // (EventType.UNRECOGNIZED), which a server newer than this SDK can send.
      log.error("Failed to encode Nexus link reference", e);
      return null;
    }
    return encode(
        LinkType.WORKFLOW_EVENT, we.getNamespace(), we.getWorkflowId(), we.getRunId(), reference);
  }

  public static io.temporal.api.nexus.v1.Link workflowLinkToNexusLink(Link.Workflow w) {
    List<Map.Entry<String, String>> query = new ArrayList<>();
    if (!w.getReason().isEmpty()) {
      query.add(param(REASON_KEY, w.getReason()));
    }
    return encode(LinkType.WORKFLOW, w.getNamespace(), w.getWorkflowId(), w.getRunId(), query);
  }

  public static io.temporal.api.nexus.v1.Link nexusOperationToNexusLink(Link.NexusOperation no) {
    return encode(
        LinkType.NEXUS_OPERATION,
        no.getNamespace(),
        no.getOperationId(),
        no.getRunId(),
        Collections.emptyList());
  }

  public static io.temporal.api.nexus.v1.Link activityToNexusLink(Link.Activity activity) {
    return encode(
        LinkType.ACTIVITY,
        activity.getNamespace(),
        activity.getActivityId(),
        activity.getRunId(),
        Collections.emptyList());
  }

  @Nullable
  public static io.temporal.api.nexus.v1.Link callbackToNexusLink(Link.Callback cb) {
    Execution execution = cb.getExecution();
    String executionType = EXECUTION_TYPE_SEGMENTS.get(execution.getType());
    if (executionType == null) {
      log.error(
          "Failed to convert Callback link to Nexus link: unsupported execution type: {}",
          execution.getType());
      return null;
    }
    List<Map.Entry<String, String>> query = new ArrayList<>();
    for (String segment : cb.getComponentPathList()) {
      query.add(param(COMPONENT_PATH_KEY, segment));
    }
    return encode(
        LinkType.CALLBACK,
        executionType,
        cb.getNamespace(),
        execution.getBusinessId(),
        execution.getRunId(),
        cb.getRequestId(),
        query);
  }

  // ===============================================================================================
  // Decode: nexus.v1.Link -> Link.
  // ===============================================================================================

  /** Dispatches on {@link io.temporal.api.nexus.v1.Link#getType()}. */
  @Nullable
  public static Link nexusLinkToLink(io.temporal.api.nexus.v1.Link nexusLink) {
    String type = nexusLink.getType();
    if (LinkType.WORKFLOW_EVENT.type.equals(type)) {
      return nexusLinkToWorkflowEvent(nexusLink);
    }
    if (LinkType.NEXUS_OPERATION.type.equals(type)) {
      return nexusLinkToNexusOperation(nexusLink);
    }
    if (LinkType.WORKFLOW.type.equals(type)) {
      return nexusLinkToWorkflowLink(nexusLink);
    }
    if (LinkType.ACTIVITY.type.equals(type)) {
      return nexusLinkToActivity(nexusLink);
    }
    if (LinkType.CALLBACK.type.equals(type)) {
      return nexusLinkToCallback(nexusLink);
    }
    log.warn("ignoring unsupported nexus link type: {}", type);
    return null;
  }

  @Nullable
  public static Link nexusLinkToWorkflowEvent(io.temporal.api.nexus.v1.Link nexusLink) {
    Decoded decoded = decode(LinkType.WORKFLOW_EVENT, nexusLink);
    if (decoded == null) {
      return null;
    }
    Link.WorkflowEvent.Builder we =
        Link.WorkflowEvent.newBuilder()
            .setNamespace(decoded.namespace)
            .setWorkflowId(decoded.id)
            .setRunId(decoded.runId);
    if (!decodeReference(we, decoded.query)) {
      return null;
    }
    return Link.newBuilder().setWorkflowEvent(we).build();
  }

  @Nullable
  public static Link nexusLinkToWorkflowLink(io.temporal.api.nexus.v1.Link nexusLink) {
    Decoded decoded = decode(LinkType.WORKFLOW, nexusLink);
    if (decoded == null) {
      return null;
    }
    Link.Workflow.Builder w =
        Link.Workflow.newBuilder()
            .setNamespace(decoded.namespace)
            .setWorkflowId(decoded.id)
            .setRunId(decoded.runId);
    String reason = decoded.query.get(REASON_KEY);
    if (reason != null) {
      w.setReason(reason);
    }
    return Link.newBuilder().setWorkflow(w).build();
  }

  @Nullable
  public static Link nexusLinkToNexusOperation(io.temporal.api.nexus.v1.Link nexusLink) {
    Decoded decoded = decode(LinkType.NEXUS_OPERATION, nexusLink);
    if (decoded == null) {
      return null;
    }
    return Link.newBuilder()
        .setNexusOperation(
            Link.NexusOperation.newBuilder()
                .setNamespace(decoded.namespace)
                .setOperationId(decoded.id)
                .setRunId(decoded.runId))
        .build();
  }

  @Nullable
  public static Link nexusLinkToActivity(io.temporal.api.nexus.v1.Link nexusLink) {
    Decoded decoded = decode(LinkType.ACTIVITY, nexusLink);
    if (decoded == null) {
      return null;
    }
    return Link.newBuilder()
        .setActivity(
            Link.Activity.newBuilder()
                .setNamespace(decoded.namespace)
                .setActivityId(decoded.id)
                .setRunId(decoded.runId))
        .build();
  }

  /** Inverse of {@link #callbackToNexusLink}. A missing component path decodes to an empty one. */
  @Nullable
  public static Link nexusLinkToCallback(io.temporal.api.nexus.v1.Link nexusLink) {
    Decoded decoded = decode(LinkType.CALLBACK, nexusLink);
    if (decoded == null) {
      return null;
    }
    ExecutionType executionType = EXECUTION_TYPE_SEGMENTS.inverse().get(decoded.keyword);
    return Link.newBuilder()
        .setCallback(
            Link.Callback.newBuilder()
                .setNamespace(decoded.namespace)
                .setExecution(
                    Execution.newBuilder()
                        .setType(executionType)
                        .setBusinessId(decoded.id)
                        .setRunId(decoded.runId))
                .setRequestId(decoded.tailId)
                .addAllComponentPath(decoded.componentPath))
        .build();
  }

  // ===============================================================================================
  // Shared encode/decode.
  // ===============================================================================================

  /** Builds a Nexus link URL for {@code linkType}, which must have a fixed keyword. */
  @Nullable
  private static io.temporal.api.nexus.v1.Link encode(
      LinkType linkType,
      String namespace,
      String id,
      String runId,
      List<Map.Entry<String, String>> queryParams) {
    return encode(linkType, linkType.keyword, namespace, id, runId, null, queryParams);
  }

  /**
   * Builds a Nexus link URL for {@code linkType}, with {@code keyword} in place of the type's own
   * for a type whose keyword varies, and {@code tailId} after the tail for a type that has one.
   *
   * <p>Concatenated rather than built with {@link URI}, because the segments are already
   * percent-encoded and {@link URI} would escape the escapes.
   */
  @Nullable
  private static io.temporal.api.nexus.v1.Link encode(
      LinkType linkType,
      String keyword,
      String namespace,
      String id,
      String runId,
      @Nullable String tailId,
      List<Map.Entry<String, String>> queryParams) {
    try {
      StringBuilder url =
          new StringBuilder(SCHEME)
              .append(":///")
              .append(NAMESPACES_SEGMENT)
              .append('/')
              .append(encodePathSegment(namespace))
              .append('/')
              .append(keyword)
              .append('/')
              .append(encodePathSegment(id))
              .append('/')
              .append(encodePathSegment(runId));
      if (linkType.tail != null) {
        url.append('/').append(linkType.tail);
      }
      if (tailId != null) {
        url.append('/').append(encodePathSegment(tailId));
      }
      if (!queryParams.isEmpty()) {
        url.append('?').append(encodeQuery(queryParams));
      }
      return io.temporal.api.nexus.v1.Link.newBuilder()
          .setUrl(url.toString())
          .setType(linkType.type)
          .build();
    } catch (Exception e) {
      log.error("Failed to encode {} Nexus link URL", linkType, e);
      return null;
    }
  }

  /** The path IDs, the execution keyword and the tail ID if any, plus the query. */
  private static final class Decoded {
    final String namespace;
    final String keyword;
    final String id;
    final String runId;
    @Nullable final String tailId;
    final Map<String, String> query;
    final List<String> componentPath;

    Decoded(
        String namespace,
        String keyword,
        String id,
        String runId,
        @Nullable String tailId,
        @Nullable String rawQuery)
        throws UnsupportedEncodingException {
      this.namespace = namespace;
      this.keyword = keyword;
      this.id = id;
      this.runId = runId;
      this.tailId = tailId;
      this.query = decodeQuery(rawQuery);
      this.componentPath = decodeQueryValues(rawQuery);
    }
  }

  /**
   * Validates a Nexus link against {@code linkType} and splits out its path IDs and query.
   *
   * <p>The declared type must match, the path must have exactly the expected segments, and no
   * segment may be empty.
   */
  @Nullable
  private static Decoded decode(LinkType linkType, io.temporal.api.nexus.v1.Link nexusLink) {
    try {
      if (!linkType.type.equals(nexusLink.getType())) {
        log.error(
            "Failed to parse Nexus link URL: cannot parse link type {} to {}",
            nexusLink.getType(),
            linkType.type);
        return null;
      }

      URI uri = new URI(nexusLink.getUrl());
      if (!SCHEME.equals(uri.getScheme())) {
        log.error("Failed to parse Nexus link URL: invalid scheme: {}", uri.getScheme());
        return null;
      }
      String rawPath = uri.getRawPath();
      if (rawPath == null) {
        log.error("Failed to parse Nexus link URL: no path: {}", nexusLink.getUrl());
        return null;
      }
      // Split the raw path: a segment may legally contain an encoded slash.
      String[] segments =
          rawPath.startsWith("/") ? rawPath.substring(1).split("/", -1) : rawPath.split("/", -1);

      if (segments.length != linkType.segmentCount()
          || !NAMESPACES_SEGMENT.equals(segments[0])
          || (linkType.keyword != null && !linkType.keyword.equals(segments[2]))
          || (linkType.tail != null && !linkType.tail.equals(segments[5]))) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", rawPath);
        return null;
      }
      for (String segment : segments) {
        if (segment.isEmpty()) {
          log.error("Failed to parse Nexus link URL: empty path segment: {}", rawPath);
          return null;
        }
      }
      if (linkType.keyword == null && !EXECUTION_TYPE_SEGMENTS.containsValue(segments[2])) {
        log.error("Failed to parse Nexus link URL: unsupported execution type: {}", segments[2]);
        return null;
      }

      return new Decoded(
          decodePathSegment(segments[1]),
          segments[2],
          decodePathSegment(segments[3]),
          decodePathSegment(segments[4]),
          linkType.hasTailId ? decodePathSegment(segments[6]) : null,
          uri.getRawQuery());
    } catch (Exception e) {
      // Swallow un-parsable links since they are not critical to processing.
      log.error("Failed to parse Nexus link URL", e);
      return null;
    }
  }

  /**
   * Percent-encodes one path segment, encoding a space as {@code %20}.
   *
   * <p>{@code java.net}'s URL codecs target HTML form data rather than general URIs, so {@link
   * URLEncoder} emits {@code +} for a space. In a path a {@code +} is a literal plus to the
   * decoding server, so in that edge case the path is incorrect and ends up with a + instead of a
   * space. Since the encoder writes a {@code +} as {@code %2B}, we can adjust for this by replacing
   * any {@code +} signs we find with {@code %2B} as we know they are encoded spaces.
   */
  private static String encodePathSegment(String segment) throws UnsupportedEncodingException {
    return URLEncoder.encode(segment, UTF_8).replace("+", "%20");
  }

  /**
   * Percent-decodes one path segment, leaving {@code +} alone.
   *
   * <p>The same form-data quirk in reverse: {@link URLDecoder} reads {@code +} as a space, which
   * would corrupt an identifier containing a literal plus. Java 8 has no percent-only decoder, so
   * pre-escape plus signs to {@code %2B} and let the form decoder hand them back unchanged.
   */
  private static String decodePathSegment(String segment) throws UnsupportedEncodingException {
    return URLDecoder.decode(segment.replace("+", "%2B"), UTF_8);
  }

  /** Form-encodes query parameters in the order given. */
  private static String encodeQuery(List<Map.Entry<String, String>> params)
      throws UnsupportedEncodingException {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> p : params) {
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(URLEncoder.encode(p.getKey(), UTF_8))
          .append('=')
          .append(URLEncoder.encode(p.getValue(), UTF_8));
    }
    return sb.toString();
  }

  /**
   * Form-decodes a raw query string.
   *
   * <p>Takes {@link URI#getRawQuery()} rather than {@link URI#getQuery()}: the latter is already
   * percent-decoded, so decoding it again throws on any value containing a bare {@code %} and
   * mis-splits values containing {@code &} or {@code =}.
   *
   * <p>Unlike a path segment, a query value is form-decoded, so {@code +} means a space. {@link
   * URLDecoder} does that before percent-decoding, which is the required order — form encoding
   * writes a literal {@code +} as {@code %2B}.
   */
  private static Map<String, String> decodeQuery(@Nullable String rawQuery)
      throws UnsupportedEncodingException {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> params = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      String[] kv = pair.split("=", 2);
      String key = URLDecoder.decode(kv[0], UTF_8);
      // First occurrence wins on a repeated key, matching api-go's Query().Get. No encoder emits
      // one, but silently preferring the last would make Java the odd SDK out.
      if (params.containsKey(key)) {
        continue;
      }
      // A key with no usable value maps to null, whether written "?k" or "?k="; callers null-check.
      String value = kv.length == 2 && !kv[1].isEmpty() ? URLDecoder.decode(kv[1], UTF_8) : null;
      params.put(key, value);
    }
    return params;
  }

  /**
   * Form-decodes every {@code componentPath} value in a raw query, in order. {@link #decodeQuery}
   * keeps only the first value of a repeated key, so this repeated param needs its own pass. A key
   * with no value yields an empty string, matching api-go's {@code Query()}.
   */
  private static List<String> decodeQueryValues(@Nullable String rawQuery)
      throws UnsupportedEncodingException {
    List<String> values = new ArrayList<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return values;
    }
    for (String pair : rawQuery.split("&")) {
      String[] kv = pair.split("=", 2);
      if (!pair.isEmpty() && COMPONENT_PATH_KEY.equals(URLDecoder.decode(kv[0], UTF_8))) {
        values.add(kv.length == 2 ? URLDecoder.decode(kv[1], UTF_8) : "");
      }
    }
    return values;
  }

  // ===============================================================================================
  // The WorkflowEvent "reference": WHICH event in the workflow's history the link points at.
  //
  // A Link.WorkflowEvent points at one specific history event. The path names the workflow
  // (namespace / workflowId / runId); the reference names the event inside it. It travels in the
  // query string rather than the path because it is optional and comes in two shapes.
  //
  // An event can be named two ways, which is why the proto models this as a oneof:
  //
  //   EventReference       by event ID, the event's position in history. Only usable once the
  //                        event exists and the caller knows its ID.
  //   RequestIdReference   by the request ID of the RPC that produced the event. Used when the
  //                        caller holds a request ID but no event ID -- a link built at the moment
  //                        a workflow is started or an update is accepted, where the event either
  //                        does not exist yet or its ID was never returned. The server resolves
  //                        the request ID to the event later.
  //
  // Both arms also carry the event type, which can be enough on its own: a link to
  // WorkflowExecutionStarted needs no event ID because that is always event 1, and the UI
  // resolves it that way (temporalio/ui, src/lib/utilities/event-link.ts). That is why eventID is
  // omitted rather than sent as 0.
  //
  // The whole link has to cross the wire as one URL string, so encodeReference flattens the oneof
  // into referenceType + eventID/requestID + eventType, and decodeReference reads those params
  // back and rebuilds it.
  // ===============================================================================================

  /** An unset oneof yields no params, so a workflow-event link can legally have an empty query. */
  private static List<Map.Entry<String, String>> encodeReference(Link.WorkflowEvent we) {
    List<Map.Entry<String, String>> query = new ArrayList<>();
    if (we.hasEventRef()) {
      Link.WorkflowEvent.EventReference ref = we.getEventRef();
      query.add(param(REFERENCE_TYPE_KEY, EVENT_REFERENCE_TYPE));
      // An unset event ID is 0, which is not a valid event ID, so omit it rather than send a zero.
      if (ref.getEventId() > 0) {
        query.add(param(EVENT_ID_KEY, String.valueOf(ref.getEventId())));
      }
      query.add(param(EVENT_TYPE_KEY, encodeEventType(ref.getEventType())));
    } else if (we.hasRequestIdRef()) {
      Link.WorkflowEvent.RequestIdReference ref = we.getRequestIdRef();
      query.add(param(REFERENCE_TYPE_KEY, REQUEST_ID_REFERENCE_TYPE));
      query.add(param(REQUEST_ID_KEY, ref.getRequestId()));
      query.add(param(EVENT_TYPE_KEY, encodeEventType(ref.getEventType())));
    }
    return query;
  }

  /**
   * Selects the arm by {@code referenceType}. Returns false if the query names no recognized
   * reference, which makes the link unusable — a workflow event link must say which event it means.
   *
   * <p>The catch is load-bearing: this runs outside {@link #decode}'s try block, and both {@link
   * Long#parseLong} and {@link EventType#valueOf} throw on malformed input.
   */
  private static boolean decodeReference(Link.WorkflowEvent.Builder we, Map<String, String> query) {
    try {
      return decodeReferenceOrThrow(we, query);
    } catch (Exception e) {
      log.error("Failed to parse Nexus link URL reference", e);
      return false;
    }
  }

  private static boolean decodeReferenceOrThrow(
      Link.WorkflowEvent.Builder we, Map<String, String> query) {
    String referenceType = query.get(REFERENCE_TYPE_KEY);
    if (EVENT_REFERENCE_TYPE.equals(referenceType)) {
      Link.WorkflowEvent.EventReference.Builder ref =
          Link.WorkflowEvent.EventReference.newBuilder();
      String eventId = query.get(EVENT_ID_KEY);
      if (eventId != null && !eventId.isEmpty()) {
        ref.setEventId(Long.parseLong(eventId));
      }
      String eventType = query.get(EVENT_TYPE_KEY);
      if (eventType != null && !eventType.isEmpty()) {
        ref.setEventType(decodeEventType(eventType));
      }
      we.setEventRef(ref);
      return true;
    }
    if (REQUEST_ID_REFERENCE_TYPE.equals(referenceType)) {
      Link.WorkflowEvent.RequestIdReference.Builder ref =
          Link.WorkflowEvent.RequestIdReference.newBuilder();
      String requestId = query.get(REQUEST_ID_KEY);
      if (requestId != null && !requestId.isEmpty()) {
        ref.setRequestId(requestId);
      }
      String eventType = query.get(EVENT_TYPE_KEY);
      if (eventType != null && !eventType.isEmpty()) {
        ref.setEventType(decodeEventType(eventType));
      }
      we.setRequestIdRef(ref);
      return true;
    }
    log.error("Failed to parse Nexus link URL: invalid reference type: {}", referenceType);
    return false;
  }

  /** Emits the short PascalCase event type name, e.g. {@code WorkflowExecutionStarted}. */
  private static String encodeEventType(EventType eventType) {
    return uniqueToSimplifiedName(eventType.name(), EVENT_TYPE_PREFIX);
  }

  /** Accepts either the {@code EVENT_TYPE_}-prefixed proto name or the short PascalCase form. */
  private static EventType decodeEventType(String eventType) {
    if (eventType.startsWith(EVENT_TYPE_PREFIX)) {
      return EventType.valueOf(eventType);
    }
    return EventType.valueOf(simplifiedToUniqueName(eventType, EVENT_TYPE_PREFIX));
  }

  private static Map.Entry<String, String> param(String key, String value) {
    return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
  }

  private LinkConverter() {}
}
