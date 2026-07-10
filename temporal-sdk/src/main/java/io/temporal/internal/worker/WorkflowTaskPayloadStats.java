package io.temporal.internal.worker;

import java.util.concurrent.atomic.AtomicLong;

public final class WorkflowTaskPayloadStats {

  private final AtomicLong uploadTimeMillis = new AtomicLong();
  private final AtomicLong downloadTimeMillis = new AtomicLong();

  private final AtomicLong uploadedPayloads = new AtomicLong();
  private final AtomicLong downloadedPayloads = new AtomicLong();

  private final AtomicLong uploadedBytes = new AtomicLong();
  private final AtomicLong downloadedBytes = new AtomicLong();

  public void recordUpload(long millis, int count, long bytes) {
    uploadTimeMillis.addAndGet(millis);
    uploadedPayloads.addAndGet(count);
    uploadedBytes.addAndGet(bytes);
  }

  public void recordDownload(long millis, int count, long bytes) {
    downloadTimeMillis.addAndGet(millis);
    downloadedPayloads.addAndGet(count);
    downloadedBytes.addAndGet(bytes);
  }

  @Override
  public String toString() {
    return "externalStorageUploadTime="
        + uploadTimeMillis.get()
        + "ms uploadedPayloads="
        + uploadedPayloads.get()
        + " uploadedBytes="
        + uploadedBytes.get()
        + " externalStorageDownloadTime="
        + downloadTimeMillis.get()
        + "ms downloadedPayloads="
        + downloadedPayloads.get()
        + " downloadedBytes="
        + downloadedBytes.get();
  }
}
