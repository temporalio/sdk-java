package io.temporal.internal.worker;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorkflowTaskPayloadStatsTest {

  @Test
  public void recordsUploadStats() {
    WorkflowTaskPayloadStats stats = new WorkflowTaskPayloadStats();

    stats.recordUpload(100, 3, 1024);

    String output = stats.toString();

    assertTrue(output.contains("externalStorageUploadTime=100ms"));
    assertTrue(output.contains("uploadedPayloads=3"));
    assertTrue(output.contains("uploadedBytes=1024"));
  }

  @Test
  public void recordsDownloadStats() {
    WorkflowTaskPayloadStats stats = new WorkflowTaskPayloadStats();

    stats.recordDownload(200, 5, 2048);

    String output = stats.toString();

    assertTrue(output.contains("externalStorageDownloadTime=200ms"));
    assertTrue(output.contains("downloadedPayloads=5"));
    assertTrue(output.contains("downloadedBytes=2048"));
  }
}
