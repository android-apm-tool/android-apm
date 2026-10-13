# Third-party analysis dependencies

The optional analysis process uses unmodified Maven artifacts, on a separate runtime classpath. Their code is not copied into the APM compiler plugin or shaded into the Gradle plugin.

| Project | Selected version | License / source |
| --- | --- | --- |
| SootUp (core, java.core, java.bytecode.frontend, callgraph, analysis.interprocedural and transitives) | 3.0.1 | LGPL-2.1-or-later; [source and license](https://github.com/soot-oss/SootUp), [published source artifacts](https://repo.maven.apache.org/maven2/org/soot-oss/) |
| Heros | 1.2.3 | LGPL-2.1; [source and license](https://github.com/soot-oss/heros), [published artifacts](https://repo.maven.apache.org/maven2/de/upb/cs/swt/heros/1.2.3/) |

Transitive dependencies retain their own licenses and notices, including ASM, Guava, SLF4J, JGraphT, Graph4J, Apache Commons, Gson and FunctionalJava. The resolved set can be inspected with `./gradlew :apm-analysis:dependencies --configuration runtimeClasspath`.

Keep upstream notices and corresponding source availability when distributing these dependencies. This repository does not replace their licenses with its own. Dependency JARs remain separate and replaceable; there is no vendored fork in this change.
