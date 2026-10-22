# Third-party notices

Project source is MIT licensed. The distributable bundles these dependencies under their own terms:

| Dependency | Version | License |
|---|---|---|
| Docling Java serve client, serve API and core | 0.7.0 | MIT, Copyright 2025 Docling Project |
| Jackson databind and core | 2.22.4 | Apache-2.0 and embedded third-party notices |
| Jackson annotations | 2.22 | Apache-2.0 |
| SQLite JDBC | 3.53.4.0 | Apache-2.0; original Zentus BSD notice retained |

Original license and notice files are preserved under [`src/main/resources/META-INF/licenses`](src/main/resources/META-INF/licenses) and inside the release JAR. Jackson core includes FastDoubleParser and Schubfach third-party license files; retain them when redistributing. SQLite native libraries retain their upstream terms. SQLite's engine is in the public domain.

[`dependency-artifacts.json`](dependency-artifacts.json) records the exact bundled Maven artifact hashes. Host-provided XWiki, Tika and SLF4J libraries and test/build dependencies are not redistributed by this project. Docling Java and Jackson packages are relocated to avoid host conflicts; SQLite's package remains intact for its native JNI bindings.
