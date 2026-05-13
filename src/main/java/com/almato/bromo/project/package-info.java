/// Project model — source roots, classpath, java release, build-system metadata.
///
/// Sealed [ProjectModel] type, [ProjectModelProvider] SPI, [com.almato.bromo.project.classpath.ClasspathService].
/// Each build system gets its own subpackage. The Maven Resolver-based provider
/// (v0; replaced post-v0 on the R1 trigger) lives at
/// [com.almato.bromo.project.maven.resolver].
package com.almato.bromo.project;
