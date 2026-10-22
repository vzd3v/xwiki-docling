package io.github.vzd3v.xwiki.docling;

/** A safe diagnostic code, never remote content or credentials. */
final class ConversionFailure extends Exception {
  private static final long serialVersionUID = 1L;
  private final boolean retryable;

  ConversionFailure(String code, boolean retryable) {
    super(code);
    this.retryable = retryable;
  }

  ConversionFailure(String code, boolean retryable, Throwable cause) {
    super(code, cause);
    this.retryable = retryable;
  }

  boolean retryable() {
    return retryable;
  }
}
