package io.temporal.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.NexusClientInterceptor;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class NexusClientOptionsTest {

  @Test
  public void testDefaultNamespaceIsDefault() {
    NexusClientOptions opts = NexusClientOptions.newBuilder().build();
    assertEquals("default", opts.getNamespace());
  }

  @Test
  public void testDefaultIdentityIsNotNull() {
    NexusClientOptions opts = NexusClientOptions.newBuilder().build();
    assertNotNull(opts.getIdentity());
    assertFalse(opts.getIdentity().isEmpty());
  }

  @Test
  public void testNewBuilderFromOptionsCopiesAllFields() {
    NexusClientInterceptor interceptor = mock(NexusClientInterceptor.class);
    DataConverter dc = mock(DataConverter.class);

    NexusClientOptions original =
        NexusClientOptions.newBuilder()
            .setNamespace("ns")
            .setIdentity("id")
            .setDataConverter(dc)
            .setExternalStorage(storage())
            .setInterceptors(Collections.singletonList(interceptor))
            .build();

    NexusClientOptions copy = NexusClientOptions.newBuilder(original).build();

    assertEquals(original.getNamespace(), copy.getNamespace());
    assertEquals(original.getIdentity(), copy.getIdentity());
    assertSame(original.getDataConverter(), copy.getDataConverter());
    assertEquals(original.getInterceptors(), copy.getInterceptors());
    assertSame(original.getExternalStorage(), copy.getExternalStorage());
  }

  @Test
  public void externalStorageDefaultsToDisabled() {
    assertNull(NexusClientOptions.newBuilder().build().getExternalStorage());
  }

  @Test
  public void externalStorageSurvivesBuild() {
    ExternalStorage storage = storage();

    NexusClientOptions options =
        NexusClientOptions.newBuilder().setExternalStorage(storage).build();

    assertSame(storage, options.getExternalStorage());
  }

  private static ExternalStorage storage() {
    return ExternalStorage.newBuilder().setDriver(driver()).build();
  }

  private static StorageDriver driver() {
    return new StorageDriver() {
      @Override
      public String getName() {
        return "test-driver";
      }

      @Override
      public String getType() {
        return "test";
      }

      @Override
      public CompletableFuture<List<StorageDriverClaim>> store(
          StorageDriverStoreContext context, List<Payload> payloads) {
        throw new UnsupportedOperationException();
      }

      @Override
      public CompletableFuture<List<Payload>> retrieve(
          StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
