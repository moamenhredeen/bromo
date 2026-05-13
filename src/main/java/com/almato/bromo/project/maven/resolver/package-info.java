/// Maven Resolver-based project loader (v0).
///
/// **This is the only package in the codebase allowed to import**
/// `org.eclipse.aether.*` **or** `org.apache.maven.*`. Architecture tests
/// enforce the boundary so the R1 replacement track (drop Maven Resolver
/// for a hand-rolled `pom.xml` parser + `~/.m2` walker) is a self-contained
/// swap — no consumer code changes.
package com.almato.bromo.project.maven.resolver;
