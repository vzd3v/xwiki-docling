package io.github.vzd3v.xwiki.docling;

import java.nio.file.Path;

@FunctionalInterface
interface Converter {
  String convert(Path file, String mime) throws Exception;
}
