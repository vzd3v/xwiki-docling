package io.github.vzd3v.xwiki.docling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Hashes {
  private Hashes() {}

  static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  static String of(String value) {
    return hex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
