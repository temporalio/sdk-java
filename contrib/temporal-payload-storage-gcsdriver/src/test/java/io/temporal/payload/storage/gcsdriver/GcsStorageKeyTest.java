package io.temporal.payload.storage.gcsdriver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;

public class GcsStorageKeyTest {
  private static final String DIGEST =
      "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

  @Test
  public void segmentEncodingMatchesGoExamples() {
    String[][] examples = {
      {null, "null"},
      {"", "null"},
      {"hello-world_v2.0", "hello-world_v2.0"},
      {"a/b", "a%2Fb"},
      {"100%done", "100%25done"},
      {"#[]*?:\"<>|", "%23%5B%5D%2A%3F%3A%22%3C%3E%7C"},
      {"a\u0000b\nc\rd\u007fe\u0080\u009f", "a%00b%0Ac%0Dd%7Fe%C2%80%C2%9F"},
      {".", "%2E"},
      {"..", "%2E%2E"},
      {"...", "..."},
      {"日本語 café ☃ 😀", "日本語 café ☃ 😀"},
      {"a+b=c@d ~!$&", "a+b=c@d ~!$&"}
    };
    for (String[] example : examples) {
      assertEquals(example[0], example[1], GcsStorageKey.escapePathSegment(example[0]));
    }
  }

  @Test
  public void workflowActivityAndUnknownNames() {
    assertEquals(
        "v0/ns/my namespace/wt/my%2Fworkflow/wi/wf id+1/ri/run=abc/d/sha256/" + DIGEST,
        GcsStorageKey.forPayload(
            new StorageDriverWorkflowInfo("my namespace", "wf id+1", "run=abc", "my/workflow"),
            DIGEST));
    assertEquals(
        "v0/ns/default/at/MyActivity/ai/act-789/ri/run-abc/d/sha256/" + DIGEST,
        GcsStorageKey.forPayload(
            new StorageDriverActivityInfo("default", "act-789", "run-abc", "MyActivity"), DIGEST));
    assertEquals("v0/d/sha256/" + DIGEST, GcsStorageKey.forPayload(null, DIGEST));
    assertEquals(
        "v0/d/sha256/" + DIGEST,
        GcsStorageKey.forPayload(new StorageDriverTargetInfo() {}, DIGEST));
  }

  @Test
  public void missingIdentityFieldsUseNullSegments() {
    assertEquals(
        "v0/ns/ns/wt/null/wi/null/ri/null/d/sha256/" + DIGEST,
        GcsStorageKey.forPayload(new StorageDriverWorkflowInfo("ns", null, "", null), DIGEST));
    assertEquals(
        "v0/ns/null/at/null/ai/null/ri/null/d/sha256/" + DIGEST,
        GcsStorageKey.forPayload(new StorageDriverActivityInfo("", "", null, ""), DIGEST));
  }

  @Test
  public void utf8LimitFallsBackWhilePreservingNamespace() {
    String longField = repeat("日", 150);
    for (StorageDriverTargetInfo target :
        new StorageDriverTargetInfo[] {
          new StorageDriverWorkflowInfo("ns/日", longField, longField, longField),
          new StorageDriverActivityInfo("ns/日", longField, longField, longField)
        }) {
      assertEquals("v0/ns/ns%2F日/d/sha256/" + DIGEST, GcsStorageKey.forPayload(target, DIGEST));
    }
    String base =
        GcsStorageKey.forPayload(new StorageDriverWorkflowInfo("ns", "x", "r", "w"), DIGEST);
    int padding = 1024 - base.getBytes(StandardCharsets.UTF_8).length;
    String exact =
        GcsStorageKey.forPayload(
            new StorageDriverWorkflowInfo("ns", "x" + repeat("a", padding), "r", "w"), DIGEST);
    assertEquals(1024, exact.getBytes(StandardCharsets.UTF_8).length);
    assertTrue(exact.contains("/wt/w/wi/"));
    assertEquals(
        "v0/ns/ns/d/sha256/" + DIGEST,
        GcsStorageKey.forPayload(
            new StorageDriverWorkflowInfo("ns", "x" + repeat("a", padding + 1), "r", "w"), DIGEST));
  }

  @Test
  public void fallbackMustAlsoFitUtf8Limit() {
    String prefix = "v0/ns/";
    String suffix = "/d/sha256/" + DIGEST;
    int available = 1024 - prefix.length() - suffix.length();
    String namespace = repeat("a", available);
    assertEquals(
        prefix + namespace + suffix,
        GcsStorageKey.forPayload(
            new StorageDriverWorkflowInfo(namespace, "long", "r", "w"), DIGEST));
    assertThrows(
        GcsStorageException.class,
        () ->
            GcsStorageKey.forPayload(
                new StorageDriverWorkflowInfo(namespace + "a", "id", "r", "w"), DIGEST));
    assertThrows(
        GcsStorageException.class,
        () ->
            GcsStorageKey.forPayload(
                new StorageDriverActivityInfo(repeat("日", 400), "id", "r", "a"), DIGEST));
  }

  private static String repeat(String value, int count) {
    return String.join("", Collections.nCopies(count, value));
  }
}
