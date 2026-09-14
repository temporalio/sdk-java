package io.temporal.internal.common;

import static io.temporal.internal.common.ProtoEnumNameUtils.*;

import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LinkConverter {

  private static final Logger log = LoggerFactory.getLogger(LinkConverter.class);

  private static final String temporalUrlScheme = "temporal";
  private static final String linkPathFormat = "temporal:///namespaces/%s/workflows/%s/%s/history";
  private static final String nexusOperationLinkPathFormat =
      "temporal:///namespaces/%s/nexus-operations/%s/%s/details";
  private static final String activityLinkPathFormat =
      "temporal:///namespaces/%s/activities/%s/%s/details";
  private static final String workflowLinkPathFormat = "temporal:///namespaces/%s/workflows/%s/%s";
  private static final String linkReferenceTypeKey = "referenceType";
  private static final String linkEventIDKey = "eventID";
  private static final String linkEventTypeKey = "eventType";
  private static final String linkRequestIDKey = "requestID";
  private static final String linkReasonKey = "reason";

  private static final String eventReferenceType =
      Link.WorkflowEvent.EventReference.getDescriptor().getName();
  private static final String requestIDReferenceType =
      Link.WorkflowEvent.RequestIdReference.getDescriptor().getName();
  private static final String workflowEventLinkType =
      Link.WorkflowEvent.getDescriptor().getFullName();
  private static final String nexusOperationLinkType =
      Link.NexusOperation.getDescriptor().getFullName();
  private static final String workflowLinkType = Link.Workflow.getDescriptor().getFullName();
  private static final String activityLinkType = Link.Activity.getDescriptor().getFullName();

  public static io.temporal.api.nexus.v1.Link workflowEventToNexusLink(Link.WorkflowEvent we) {
    try {

      String url =
          String.format(
              linkPathFormat,
              URLEncoder.encode(we.getNamespace(), StandardCharsets.UTF_8.toString()),
              // The 'replace' below handles spaces - the encoder will convert them to a plus,
              // which the UI then handles as a plus, thus breaking the link as the
              // space is lost.
              // It's a known quirk with the URLEncoder as it encodes for forms, not general URIs.
              // Only done for the WorkflowId as the other two are values we control,
              // and will never have spaces.
              URLEncoder.encode(we.getWorkflowId(), StandardCharsets.UTF_8.toString())
                  .replace("+", "%20"),
              URLEncoder.encode(we.getRunId(), StandardCharsets.UTF_8.toString()));

      List<Map.Entry<String, String>> queryParams = new ArrayList<>();
      if (we.hasEventRef()) {
        queryParams.add(new SimpleImmutableEntry<>(linkReferenceTypeKey, eventReferenceType));
        Link.WorkflowEvent.EventReference eventRef = we.getEventRef();
        if (eventRef.getEventId() > 0) {
          queryParams.add(
              new SimpleImmutableEntry<>(linkEventIDKey, String.valueOf(eventRef.getEventId())));
        }
        final String eventType =
            URLEncoder.encode(
                encodeEventType(eventRef.getEventType()), StandardCharsets.UTF_8.toString());
        queryParams.add(new SimpleImmutableEntry<>(linkEventTypeKey, eventType));
      } else if (we.hasRequestIdRef()) {
        queryParams.add(new SimpleImmutableEntry<>(linkReferenceTypeKey, requestIDReferenceType));
        Link.WorkflowEvent.RequestIdReference requestIDRef = we.getRequestIdRef();
        final String requestID =
            URLEncoder.encode(requestIDRef.getRequestId(), StandardCharsets.UTF_8.toString());
        queryParams.add(new SimpleImmutableEntry<>(linkRequestIDKey, requestID));
        final String eventType =
            URLEncoder.encode(
                encodeEventType(requestIDRef.getEventType()), StandardCharsets.UTF_8.toString());
        queryParams.add(new SimpleImmutableEntry<>(linkEventTypeKey, eventType));
      }

      url +=
          "?"
              + queryParams.stream()
                  .map((item) -> item.getKey() + "=" + item.getValue())
                  .collect(Collectors.joining("&"));

      return io.temporal.api.nexus.v1.Link.newBuilder()
          .setUrl(url)
          .setType(we.getDescriptorForType().getFullName())
          .build();
    } catch (Exception e) {
      log.error("Failed to encode Nexus link URL", e);
    }
    return null;
  }

  /**
   * Converts a {@link Link.Workflow} to a Nexus link. A workflow link addresses a workflow
   * execution as a whole rather than one event within it, so the URL uses the workflow path and
   * carries no event path suffix and no reference query params. It is used when there is no history
   * event to point at, for example a Query or a rejected Update. The optional {@code reason}
   * explaining why the link exists is carried as a query param.
   */
  public static io.temporal.api.nexus.v1.Link workflowLinkToNexusLink(Link.Workflow w) {
    try {
      String url =
          String.format(
              workflowLinkPathFormat,
              encodePathSegment(w.getNamespace()),
              encodePathSegment(w.getWorkflowId()),
              encodePathSegment(w.getRunId()));
      if (!w.getReason().isEmpty()) {
        url +=
            "?"
                + linkReasonKey
                + "="
                + URLEncoder.encode(w.getReason(), StandardCharsets.UTF_8.toString());
      }
      return io.temporal.api.nexus.v1.Link.newBuilder()
          .setUrl(url)
          .setType(workflowLinkType)
          .build();
    } catch (Exception e) {
      log.error("Failed to convert WorkflowLink {} to NexusLink", w, e);
      return null;
    }
  }

  public static Link nexusLinkToWorkflowEvent(io.temporal.api.nexus.v1.Link nexusLink) {
    Link.Builder link = Link.newBuilder();
    try {
      URI uri = new URI(nexusLink.getUrl());

      if (!uri.getScheme().equals("temporal")) {
        log.error("Failed to parse Nexus link URL: invalid scheme: {}", uri.getScheme());
        return null;
      }

      StringTokenizer st = new StringTokenizer(uri.getRawPath(), "/");
      if (!st.nextToken().equals("namespaces")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String namespace = decodePathSegment(st.nextToken());
      if (!st.nextToken().equals("workflows")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String workflowID = decodePathSegment(st.nextToken());
      String runID = decodePathSegment(st.nextToken());
      if (!st.hasMoreTokens() || !st.nextToken().equals("history")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }

      Link.WorkflowEvent.Builder we =
          Link.WorkflowEvent.newBuilder()
              .setNamespace(namespace)
              .setWorkflowId(workflowID)
              .setRunId(runID);

      Map<String, String> queryParams = parseQueryParams(uri);
      String referenceType = queryParams.get(linkReferenceTypeKey);
      if (referenceType.equals(eventReferenceType)) {
        Link.WorkflowEvent.EventReference.Builder eventRef =
            Link.WorkflowEvent.EventReference.newBuilder();
        String eventID = queryParams.get(linkEventIDKey);
        if (eventID != null && !eventID.isEmpty()) {
          eventRef.setEventId(Long.parseLong(eventID));
        }
        String eventType = queryParams.get(linkEventTypeKey);
        if (eventType != null && !eventType.isEmpty()) {
          eventRef.setEventType(decodeEventType(eventType));
        }
        we.setEventRef(eventRef);
      } else if (referenceType.equals(requestIDReferenceType)) {
        Link.WorkflowEvent.RequestIdReference.Builder requestIDRef =
            Link.WorkflowEvent.RequestIdReference.newBuilder();
        String requestID = queryParams.get(linkRequestIDKey);
        if (requestID != null && !requestID.isEmpty()) {
          requestIDRef.setRequestId(requestID);
        }
        String eventType = queryParams.get(linkEventTypeKey);
        if (eventType != null && !eventType.isEmpty()) {
          requestIDRef.setEventType(decodeEventType(eventType));
        }
        we.setRequestIdRef(requestIDRef);
      } else {
        log.error("Failed to parse Nexus link URL: invalid reference type: {}", referenceType);
        return null;
      }

      link.setWorkflowEvent(we);
    } catch (Exception e) {
      // Swallow un-parsable links since they are not critical to processing
      log.error("Failed to parse Nexus link URL", e);
      return null;
    }
    return link.build();
  }

  public static Link nexusLinkToWorkflowLink(io.temporal.api.nexus.v1.Link nexusLink) {
    if (!workflowLinkType.equals(nexusLink.getType())) {
      log.error(
          "Failed to parse Nexus link URL: cannot parse link type {} to {}",
          nexusLink.getType(),
          workflowLinkType);
      return null;
    }
    Link.Builder link = Link.newBuilder();
    try {
      URI uri = new URI(nexusLink.getUrl());

      // Compared in this order so a URL with no scheme at all reports the invalid scheme rather
      // than throwing.
      if (!temporalUrlScheme.equals(uri.getScheme())) {
        log.error("Failed to parse Nexus link URL: invalid scheme: {}", uri.getScheme());
        return null;
      }

      StringTokenizer st = new StringTokenizer(uri.getRawPath(), "/");
      if (!st.nextToken().equals("namespaces")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String namespace = decodePathSegment(st.nextToken());
      if (!st.nextToken().equals("workflows")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String workflowID = decodePathSegment(st.nextToken());
      String runID = decodePathSegment(st.nextToken());
      // The run ID ends a workflow link, so anything trailing means this is a different link
      // shape. In particular this rejects the workflow-event form, which ends in "/history".
      if (st.hasMoreTokens()) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }

      Link.Workflow.Builder w =
          Link.Workflow.newBuilder()
              .setNamespace(namespace)
              .setWorkflowId(workflowID)
              .setRunId(runID);
      String reason = rawQueryParam(uri, linkReasonKey);
      if (reason != null) {
        w.setReason(reason);
      }

      link.setWorkflow(w);
    } catch (Exception e) {
      // Swallow un-parsable links since they are not critical to processing.
      log.error("Failed to parse Nexus link URL", e);
      return null;
    }
    return link.build();
  }

  /**
   * Dispatches on the oneof variant of {@code commonLink} and converts to the matching {@link
   * io.temporal.api.nexus.v1.Link}. Returns {@code null} if no variant is set or encoding fails.
   */
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
    return null;
  }

  /**
   * Dispatches on {@link io.temporal.api.nexus.v1.Link#getType()} and converts to the matching
   * {@link Link} variant. Returns {@code null} for unknown or unparseable types.
   */
  public static Link nexusLinkToLink(io.temporal.api.nexus.v1.Link nexusLink) {
    String type = nexusLink.getType();
    if (workflowEventLinkType.equals(type)) {
      return nexusLinkToWorkflowEvent(nexusLink);
    }
    if (nexusOperationLinkType.equals(type)) {
      return nexusLinkToNexusOperation(nexusLink);
    }
    if (workflowLinkType.equals(type)) {
      return nexusLinkToWorkflowLink(nexusLink);
    }
    if (activityLinkType.equals(type)) {
      return nexusLinkToActivity(nexusLink);
    }
    log.warn("ignoring unsupported nexus link type: {}", type);
    return null;
  }

  public static io.temporal.api.nexus.v1.Link activityToNexusLink(Link.Activity activity) {
    try {
      String url =
          String.format(
              activityLinkPathFormat,
              URLEncoder.encode(activity.getNamespace(), StandardCharsets.UTF_8.toString()),
              URLEncoder.encode(activity.getActivityId(), StandardCharsets.UTF_8.toString())
                  .replace("+", "%20"),
              URLEncoder.encode(activity.getRunId(), StandardCharsets.UTF_8.toString()));
      return io.temporal.api.nexus.v1.Link.newBuilder()
          .setUrl(url)
          .setType(activityLinkType)
          .build();
    } catch (Exception e) {
      log.error("Failed to encode activity Nexus link URL", e);
    }
    return null;
  }

  public static Link nexusLinkToActivity(io.temporal.api.nexus.v1.Link nexusLink) {
    if (!activityLinkType.equals(nexusLink.getType())) {
      log.error(
          "Failed to parse Nexus link URL: cannot parse link type {} to {}",
          nexusLink.getType(),
          activityLinkType);
      return null;
    }
    Link.Builder link = Link.newBuilder();
    try {
      URI uri = new URI(nexusLink.getUrl());
      if (!"temporal".equals(uri.getScheme())) {
        log.error("Failed to parse Nexus link URL: invalid scheme: {}", uri.getScheme());
        return null;
      }
      StringTokenizer st = new StringTokenizer(uri.getRawPath(), "/");
      if (!st.hasMoreTokens() || !st.nextToken().equals("namespaces")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String namespace = decodePathSegment(st.nextToken());
      if (!st.hasMoreTokens() || !st.nextToken().equals("activities")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String activityId = decodePathSegment(st.nextToken());
      if (!st.hasMoreTokens()) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String runId = decodePathSegment(st.nextToken());
      if (!st.hasMoreTokens() || !st.nextToken().equals("details")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      link.setActivity(
          Link.Activity.newBuilder()
              .setNamespace(namespace)
              .setActivityId(activityId)
              .setRunId(runId));
    } catch (Exception e) {
      log.error("Failed to parse activity Nexus link URL", e);
      return null;
    }
    return link.build();
  }

  public static io.temporal.api.nexus.v1.Link nexusOperationToNexusLink(Link.NexusOperation no) {
    try {
      String url =
          String.format(
              nexusOperationLinkPathFormat,
              URLEncoder.encode(no.getNamespace(), StandardCharsets.UTF_8.toString()),
              // See the WorkflowId comment in workflowEventToNexusLink for why '+' is rewritten to
              // '%20'. OperationId is user-supplied and can legally contain spaces.
              URLEncoder.encode(no.getOperationId(), StandardCharsets.UTF_8.toString())
                  .replace("+", "%20"),
              URLEncoder.encode(no.getRunId(), StandardCharsets.UTF_8.toString()));
      return io.temporal.api.nexus.v1.Link.newBuilder()
          .setUrl(url)
          .setType(nexusOperationLinkType)
          .build();
    } catch (Exception e) {
      log.error("Failed to encode Nexus operation link URL", e);
    }
    return null;
  }

  public static Link nexusLinkToNexusOperation(io.temporal.api.nexus.v1.Link nexusLink) {
    if (!nexusOperationLinkType.equals(nexusLink.getType())) {
      log.error(
          "Failed to parse Nexus link URL: cannot parse link type {} to {}",
          nexusLink.getType(),
          nexusOperationLinkType);
      return null;
    }
    Link.Builder link = Link.newBuilder();
    try {
      URI uri = new URI(nexusLink.getUrl());

      if (!"temporal".equals(uri.getScheme())) {
        log.error("Failed to parse Nexus link URL: invalid scheme: {}", uri.getScheme());
        return null;
      }

      StringTokenizer st = new StringTokenizer(uri.getRawPath(), "/");
      if (!st.hasMoreTokens() || !st.nextToken().equals("namespaces")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String namespace = decodePathSegment(st.nextToken());
      if (!st.hasMoreTokens() || !st.nextToken().equals("nexus-operations")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String operationId = decodePathSegment(st.nextToken());
      if (!st.hasMoreTokens()) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String runId = decodePathSegment(st.nextToken());
      if (!st.hasMoreTokens() || !st.nextToken().equals("details")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }

      link.setNexusOperation(
          Link.NexusOperation.newBuilder()
              .setNamespace(namespace)
              .setOperationId(operationId)
              .setRunId(runId));
    } catch (Exception e) {
      log.error("Failed to parse Nexus link URL", e);
      return null;
    }
    return link.build();
  }

  /**
   * Percent-encodes a single URL path segment. {@link URLEncoder} targets form encoding, where a
   * space becomes '+', so rewrite it to "%20" as required for a path.
   */
  private static String encodePathSegment(String value) throws UnsupportedEncodingException {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20");
  }

  /**
   * Percent-decodes a single URL path segment. {@link URLDecoder} targets form decoding, where '+'
   * means a space, but in a path a '+' is a literal character. Pre-escaping '+' as "%2B" keeps it
   * literal while leaving genuine percent escapes such as "%20" for the decoder to handle.
   */
  private static String decodePathSegment(String value) throws UnsupportedEncodingException {
    return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.toString());
  }

  /**
   * Reads a single param out of the raw, still-encoded query string, or returns null when the param
   * is absent. Unlike {@link #parseQueryParams} the value is decoded exactly once, so values that
   * themselves contain '=' or '&' survive the round trip.
   */
  private static String rawQueryParam(URI uri, String key) throws UnsupportedEncodingException {
    final String rawQuery = uri.getRawQuery();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return null;
    }
    for (String pair : rawQuery.split("&")) {
      final String[] kv = pair.split("=", 2);
      if (kv[0].equals(key)) {
        return kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8.toString()) : "";
      }
    }
    return null;
  }

  private static Map<String, String> parseQueryParams(URI uri) throws UnsupportedEncodingException {
    final String query = uri.getQuery();
    if (query == null || query.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> queryParams = new HashMap<>();
    for (String pair : query.split("&")) {
      final String[] kv = pair.split("=", 2);
      final String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8.toString());
      final String value =
          kv.length == 2 && !kv[1].isEmpty()
              ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8.toString())
              : null;
      queryParams.put(key, value);
    }
    return queryParams;
  }

  private static String encodeEventType(EventType eventType) {
    return uniqueToSimplifiedName(eventType.name(), EVENT_TYPE_PREFIX);
  }

  private static EventType decodeEventType(String eventType) {
    // Have to handle the SCREAMING_CASE enum or the traditional temporal PascalCase enum to
    // EventType
    if (eventType.startsWith(EVENT_TYPE_PREFIX)) {
      return EventType.valueOf(eventType);
    }
    return EventType.valueOf(simplifiedToUniqueName(eventType, EVENT_TYPE_PREFIX));
  }
}
