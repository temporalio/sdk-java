package io.temporal.payload.storage.gcsdriver.gcssdk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolverRegistry;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.conscrypt.Conscrypt;
import org.junit.Test;

/**
 * Guards the runtime providers and native library needed by the Google client's DirectPath route.
 */
public class GcsGrpcRuntimeTest {
  @Test
  public void directPathResolversAndLoadBalancersAreRegistered() {
    NameResolverRegistry resolvers = NameResolverRegistry.getDefaultRegistry();
    assertNotNull("Missing google-c2p resolver", resolvers.getProviderForScheme("google-c2p"));
    assertNotNull("Missing xDS resolver", resolvers.getProviderForScheme("xds"));
    LoadBalancerRegistry balancers = LoadBalancerRegistry.getDefaultRegistry();
    for (String policy :
        new String[] {
          "cds_experimental",
          "priority_experimental",
          "weighted_target_experimental",
          "cluster_manager_experimental",
          "cluster_impl_experimental",
          "round_robin"
        }) {
      assertNotNull("Missing load balancer " + policy, balancers.getProvider(policy));
    }
  }

  @Test
  public void conscryptKeepsItsOriginalClassesAndLinuxNativeLibraries() {
    for (String resource :
        new String[] {
          "/org/conscrypt/NativeCrypto.class",
          "/META-INF/native/libconscrypt_openjdk_jni-linux-x86_64.so",
          "/META-INF/native/libconscrypt_openjdk_jni-linux-aarch_64.so"
        }) {
      assertNotNull(
          "Missing unrelocated Conscrypt resource " + resource,
          Conscrypt.class.getResource(resource));
    }
  }

  @Test
  public void conscryptNativeCryptoLoadsAndEncryptsOnLinux() throws Exception {
    String arch = System.getProperty("os.arch");
    assumeTrue(
        System.getProperty("os.name").equals("Linux")
            && (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("aarch64")));
    Conscrypt.checkAvailability();
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", Conscrypt.newProvider());
    SecretKeySpec key = new SecretKeySpec(new byte[16], "AES");
    GCMParameterSpec parameters = new GCMParameterSpec(128, new byte[12]);
    byte[] plaintext = "DirectPath native crypto".getBytes(StandardCharsets.UTF_8);
    cipher.init(Cipher.ENCRYPT_MODE, key, parameters);
    byte[] encrypted = cipher.doFinal(plaintext);
    cipher.init(Cipher.DECRYPT_MODE, key, parameters);
    assertArrayEquals(plaintext, cipher.doFinal(encrypted));
  }
}
