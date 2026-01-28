package io.temporal.serviceclient;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class SimpleSslContextBuilderTest {
  @Test
  public void ableToLoadPKCS12Key() throws IOException {
    try (InputStream in =
        SimpleSslContextBuilderTest.class.getClassLoader().getResourceAsStream("pkcs12-key.pfx")) {
      Preconditions.checkState(in != null);
      SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS12(in);
      builder.build();
    }
  }

  // to give easier API for configuration to users we allow null inputs
  @Test
  public void nullInputIsAcceptedForPKCS12Key() throws IOException {
    SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS12(null);
    builder.build();
  }

  @Test
  public void ableToLoadPKCS8Key() throws IOException {
    try (InputStream pkIn =
            SimpleSslContextBuilderTest.class.getClassLoader().getResourceAsStream("pkcs8-pk.pem");
        InputStream crtIn =
            SimpleSslContextBuilderTest.class
                .getClassLoader()
                .getResourceAsStream("pkcs8-crt-chain.pem")) {
      Preconditions.checkState(pkIn != null);
      Preconditions.checkState(crtIn != null);

      SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS8(crtIn, pkIn);
      builder.build();
    }
  }

  // to give easier API for configuration to users we allow null inputs
  @Test
  public void nullInputIsAcceptedForPKCS8Key() throws IOException {
    SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS8(null, null);
    builder.build();
  }

  @Test
  public void ableToCreateWithoutKeyOrCerts() throws IOException {
    SimpleSslContextBuilder builder = SimpleSslContextBuilder.noKeyOrCertChain();
    builder.build();
  }
}
