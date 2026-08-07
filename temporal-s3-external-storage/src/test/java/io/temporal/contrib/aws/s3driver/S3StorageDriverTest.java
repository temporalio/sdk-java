package io.temporal.contrib.aws.s3driver;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.temporal.common.converter.StorageDriverClaim;
import io.temporal.common.converter.StorageDriverRetrieveContext;
import io.temporal.common.converter.StorageDriverStoreContext;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class S3StorageDriverTest {

  private S3Client mockClient;
  private S3StorageDriver driver;

  @Before
  public void setUp() {
    mockClient = mock(S3Client.class);
    S3StorageDriverOptions options =
        S3StorageDriverOptions.newBuilder()
            .setClient(mockClient)
            .setBucket("test-bucket")
            .setKeyPrefix("temporal/")
            .build();
    driver = new S3StorageDriver(options);
  }

  @Test
  public void testNameAndType() {
    assertEquals("aws.s3driver", driver.name());
    assertEquals("aws.s3driver", driver.type());
  }

  @Test
  public void testStoreAndRetrieve() {
    byte[] payload = "test payload data".getBytes(StandardCharsets.UTF_8);
    StorageDriverStoreContext storeCtx =
        StorageDriverStoreContext.forWorkflow("default", "workflow-123", "run-456");

    // Store
    List<StorageDriverClaim> claims = driver.store(storeCtx, Collections.singletonList(payload));
    assertEquals(1, claims.size());

    StorageDriverClaim claim = claims.get(0);
    assertEquals("test-bucket", claim.getClaimData().get("bucket"));
    assertNotNull(claim.getClaimData().get("key"));
    assertTrue(claim.getClaimData().get("key").startsWith("temporal/default/workflow-123/"));

    // Verify put was called
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockClient).putObject(eq("test-bucket"), keyCaptor.capture(), eq(payload));
    String storedKey = keyCaptor.getValue();

    // Retrieve
    when(mockClient.getObject("test-bucket", storedKey)).thenReturn(payload);
    StorageDriverRetrieveContext retrieveCtx =
        StorageDriverRetrieveContext.create("default", "workflow-123");
    List<byte[]> retrieved = driver.retrieve(retrieveCtx, claims);
    assertEquals(1, retrieved.size());
    assertArrayEquals(payload, retrieved.get(0));
  }

  @Test
  public void testContentAddressedKeys() {
    byte[] payload = "same data".getBytes(StandardCharsets.UTF_8);
    StorageDriverStoreContext ctx = StorageDriverStoreContext.forWorkflow("ns", "wf", null);

    // Same payload should produce same hash/key.
    List<StorageDriverClaim> claims1 = driver.store(ctx, Collections.singletonList(payload));
    List<StorageDriverClaim> claims2 = driver.store(ctx, Collections.singletonList(payload));
    assertEquals(
        claims1.get(0).getClaimData().get("key"), claims2.get(0).getClaimData().get("key"));
  }

  @Test
  public void testActivityContext() {
    byte[] payload = "activity data".getBytes(StandardCharsets.UTF_8);
    StorageDriverStoreContext ctx =
        StorageDriverStoreContext.forActivity("ns", "wf", "run", "MyActivity");

    List<StorageDriverClaim> claims = driver.store(ctx, Collections.singletonList(payload));
    String key = claims.get(0).getClaimData().get("key");
    assertTrue("Key should contain activity type", key.contains("MyActivity/"));
  }

  @Test
  public void testSha256Hex() {
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    String hash = S3StorageDriver.sha256Hex(data);
    assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
  }

  @Test
  public void testCustomDriverName() {
    S3StorageDriverOptions options =
        S3StorageDriverOptions.newBuilder()
            .setClient(mockClient)
            .setBucket("bucket")
            .setDriverName("my-custom-s3")
            .build();
    S3StorageDriver customDriver = new S3StorageDriver(options);
    assertEquals("my-custom-s3", customDriver.name());
    assertEquals("aws.s3driver", customDriver.type());
  }
}
