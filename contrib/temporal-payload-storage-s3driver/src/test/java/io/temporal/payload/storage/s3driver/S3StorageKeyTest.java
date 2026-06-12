package io.temporal.payload.storage.s3driver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class S3StorageKeyTest {

  @Test
  public void escapesEmptyAndNullAsNull() {
    assertEquals("null", S3StorageKey.escapePathSegment(""));
    assertEquals("null", S3StorageKey.escapePathSegment(null));
  }

  @Test
  public void leavesOnlyAwsSafePunctuationUnescaped() {
    assertEquals("AZaz09-_.", S3StorageKey.escapePathSegment("AZaz09-_."));
  }

  @Test
  public void escapesPunctuationOutsideTheSafeSet() {
    // '~' and these sub-delims are not in AWS's safe set, so (unlike Python/Go) they are escaped.
    assertEquals("%7E%24%26%2B%3A%3D%40", S3StorageKey.escapePathSegment("~$&+:=@"));
  }

  @Test
  public void percentEncodesReservedCharactersAndSpace() {
    assertEquals("a%2Fb%20c", S3StorageKey.escapePathSegment("a/b c"));
  }

  @Test
  public void percentEncodesMultibyteUtf8() {
    // 'é' (U+00E9) is the two UTF-8 bytes C3 A9; '€' (U+20AC) is the three bytes E2 82 AC.
    assertEquals("caf%C3%A9", S3StorageKey.escapePathSegment("café"));
    assertEquals("%E2%82%AC", S3StorageKey.escapePathSegment("€"));
  }
}
