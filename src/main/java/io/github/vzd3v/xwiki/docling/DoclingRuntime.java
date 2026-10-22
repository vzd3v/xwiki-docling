package io.github.vzd3v.xwiki.docling;

/** Shared between native Tika ServiceLoader instances and the XWiki lifecycle component. */
final class DoclingRuntime {
  private DoclingRuntime() {}

  private static volatile ConversionService service;

  static ConversionService get() {
    return service;
  }

  static synchronized void install(ConversionService next) {
    if (service != null) throw new IllegalStateException("Docling runtime is already installed");
    service = next;
  }

  static synchronized ConversionService detach() {
    ConversionService old = service;
    service = null;
    return old;
  }
}
