package io.temporal.payload.storage.gcsdriver.gcssdk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.GrpcStorageOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.protobuf.ByteString;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.alts.AltsContextUtil;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import io.temporal.payload.storage.gcsdriver.GcsStorageDriver;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercises gRPC against storage-testbench or verifies DirectPath against a real bucket on GCE. */
public class GcsStorageClientAdapterGrpcIntegrationTest {
  private Storage storage;
  private ExecutorService executor;
  private GcsStorageClientAdapter adapter;
  private GcsStorageDriver driver;
  private String bucket;
  private String namespace;
  private String prefix;
  private boolean directPath;
  private final TransportRecorder transport = new TransportRecorder();

  @Before
  public void setUp() {
    bucket = System.getenv("TEMPORAL_GCS_DIRECTPATH_BUCKET");
    String endpoint = System.getenv("TEMPORAL_GCS_TESTBENCH_ENDPOINT");
    directPath = bucket != null && !bucket.isEmpty();
    assumeTrue(
        "Set TEMPORAL_GCS_TESTBENCH_ENDPOINT or TEMPORAL_GCS_DIRECTPATH_BUCKET",
        directPath || (endpoint != null && !endpoint.isEmpty()));
    GrpcStorageOptions.Builder options =
        StorageOptions.grpc()
            .setEnableGrpcClientMetrics(false)
            .setGrpcInterceptorProvider(() -> Collections.singletonList(transport));
    if (!directPath) {
      bucket = "temporal-test-" + UUID.randomUUID();
      options
          .setHost(endpoint)
          .setProjectId("temporal-test")
          .setCredentials(NoCredentials.getInstance())
          .setAttemptDirectPath(false);
    }
    storage = options.build().getService();
    if (!directPath) {
      storage.create(BucketInfo.of(bucket));
    }
    executor = Executors.newFixedThreadPool(4);
    adapter = new GcsStorageClientAdapter(storage, executor);
    driver = GcsStorageDriver.newBuilder().setClient(adapter).setBucket(bucket).build();
    namespace = "grpc-test-" + UUID.randomUUID();
    prefix = "v0/ns/" + namespace + "/";
  }

  @After
  public void tearDown() throws Exception {
    if (executor != null) {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
    if (storage != null) {
      try {
        if (prefix != null) {
          for (Blob blob :
              storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll()) {
            storage.delete(blob.getBlobId());
          }
        }
        if (!directPath) {
          storage.delete(bucket);
        }
      } finally {
        storage.close();
      }
    }
  }

  @Test(timeout = 180000)
  public void completePayloadRoundTripsAndDeduplicates() throws Exception {
    StorageDriverStoreContext context =
        () -> new StorageDriverWorkflowInfo(namespace, "日本語 😀 /%+", "run", "Workflow");
    for (int size : new int[] {0, 256 * 1024, 6 * 1024 * 1024}) {
      byte[] data = new byte[size];
      new Random(size).nextBytes(data);
      Payload original =
          Payload.newBuilder()
              .setData(ByteString.copyFrom(data))
              .putMetadata("encoding", ByteString.copyFromUtf8("binary/plain"))
              .putMetadata("custom", ByteString.copyFrom(new byte[] {0, -1}))
              .build();
      List<StorageDriverClaim> claims =
          driver.store(context, Collections.singletonList(original)).get(60, TimeUnit.SECONDS);
      String name = claims.get(0).getClaimData().get("object_name");
      Long generation = storage.get(bucket, name).getGeneration();
      List<StorageDriverClaim> duplicate =
          driver.store(context, Collections.singletonList(original)).get(60, TimeUnit.SECONDS);
      assertEquals(claims.get(0).getClaimData(), duplicate.get(0).getClaimData());
      assertEquals(generation, storage.get(bucket, name).getGeneration());
      assertEquals(
          Collections.singletonList(original),
          driver.retrieve(new StorageDriverRetrieveContext() {}, claims).get(60, TimeUnit.SECONDS));
    }
    transport.assertDataTransport(directPath);
  }

  @Test(timeout = 180000)
  public void concurrentCreatesPreserveTheFirstObject() throws Exception {
    String name = prefix + "concurrent";
    List<CompletableFuture<Void>> uploads = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      uploads.add(adapter.putObjectIfAbsent(bucket, name, new byte[] {(byte) i}));
    }
    CompletableFuture.allOf(uploads.toArray(new CompletableFuture<?>[0])).get(60, TimeUnit.SECONDS);
    byte[] stored = adapter.getObject(bucket, name).get(60, TimeUnit.SECONDS);
    assertEquals(1, stored.length);
    assertTrue(stored[0] >= 0 && stored[0] < 8);
    Long generation = storage.get(bucket, name).getGeneration();
    adapter.putObjectIfAbsent(bucket, name, new byte[] {99}).get(60, TimeUnit.SECONDS);
    assertArrayEquals(stored, adapter.getObject(bucket, name).get(60, TimeUnit.SECONDS));
    assertEquals(generation, storage.get(bucket, name).getGeneration());
    transport.assertDataTransport(directPath);
  }

  @Test(timeout = 180000)
  public void missingObjectsPropagate404() throws Exception {
    ExecutionException error =
        assertThrows(
            ExecutionException.class,
            () -> adapter.getObject(bucket, prefix + "missing").get(60, TimeUnit.SECONDS));
    assertTrue(error.getCause() instanceof StorageException);
    assertEquals(404, ((StorageException) error.getCause()).getCode());
  }

  private static final class TransportRecorder implements ClientInterceptor {
    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
      return next.newCall(
          method,
          options.withStreamTracerFactory(
              new ClientStreamTracer.Factory() {
                @Override
                public ClientStreamTracer newClientStreamTracer(
                    ClientStreamTracer.StreamInfo info, Metadata headers) {
                  return new ClientStreamTracer() {
                    private Attributes attributes;

                    @Override
                    public void streamCreated(Attributes transportAttrs, Metadata requestHeaders) {
                      attributes = transportAttrs;
                    }

                    @Override
                    public void streamClosed(Status status) {
                      if (status.isOk() && attributes != null) {
                        observations.add(
                            new Observation(
                                method.getFullMethodName(),
                                attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR),
                                AltsContextUtil.check(attributes)));
                      }
                    }
                  };
                }
              }));
    }

    void assertDataTransport(boolean directPath) {
      boolean read = false;
      boolean write = false;
      for (Observation observation : observations) {
        boolean isRead = observation.method.endsWith("/ReadObject");
        boolean isWrite = observation.method.endsWith("/WriteObject");
        if (!isRead && !isWrite) {
          continue;
        }
        read |= isRead;
        write |= isWrite;
        System.out.println(observation);
        if (directPath) {
          assertTrue("Expected ALTS on " + observation, observation.alts);
          assertTrue(
              "Expected a DirectPath peer on " + observation, observation.isDirectPathPeer());
        }
      }
      assertTrue("No successful gRPC ReadObject stream observed", read);
      assertTrue("No successful gRPC WriteObject stream observed", write);
    }
  }

  private static final class Observation {
    private final String method;
    private final SocketAddress peer;
    private final boolean alts;

    private Observation(String method, SocketAddress peer, boolean alts) {
      this.method = method;
      this.peer = peer;
      this.alts = alts;
    }

    private boolean isDirectPathPeer() {
      if (!(peer instanceof InetSocketAddress)) {
        return false;
      }
      byte[] address = ((InetSocketAddress) peer).getAddress().getAddress();
      // DirectPath uses 34.126.0.0/18 or 2001:4860:8040::/42.
      return (address.length == 4
              && address[0] == 34
              && address[1] == 126
              && (address[2] & 255) < 64)
          || (address.length == 16
              && address[0] == 0x20
              && address[1] == 0x01
              && address[2] == 0x48
              && address[3] == 0x60
              && (address[4] & 255) == 0x80
              && (address[5] & 0xc0) == 0x40);
    }

    @Override
    public String toString() {
      return method + " peer=" + peer + " ALTS=" + alts;
    }
  }
}
