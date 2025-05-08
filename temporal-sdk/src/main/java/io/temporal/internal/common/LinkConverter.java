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

  private static final String linkPathFormat = "temporal:///namespaces/%s/workflows/%s/%s/history";
  private static final String linkReferenceTypeKey = "referenceType";
  private static final String linkEventIDKey = "eventID";
  private static final String linkEventTypeKey = "eventType";
  private static final String linkRequestIDKey = "requestID";

  private static final String eventReferenceType =
      Link.WorkflowEvent.EventReference.getDescriptor().getName();
  private static final String requestIDReferenceType =
      Link.WorkflowEvent.RequestIdReference.getDescriptor().getName();

  public static io.temporal.api.nexus.v1.Link workflowEventToNexusLink(Link.WorkflowEvent we) {
    try {
      String url =
          String.format(
              linkPathFormat,
              URLEncoder.encode(we.getNamespace(), StandardCharsets.UTF_8.toString()),
              URLEncoder.encode(we.getWorkflowId(), StandardCharsets.UTF_8.toString()),
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
      String namespace = URLDecoder.decode(st.nextToken(), StandardCharsets.UTF_8.toString());
      if (!st.nextToken().equals("workflows")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String workflowID = URLDecoder.decode(st.nextToken(), StandardCharsets.UTF_8.toString());
      String runID = URLDecoder.decode(st.nextToken(), StandardCharsets.UTF_8.toString());
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
