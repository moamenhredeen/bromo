/// JDK source attachment.
///
/// Wraps `$JAVA_HOME/lib/src.zip` so goto-definition into `java.base` /
/// `java.xml` / etc. can resolve to actual source files. Matches the source
/// attachment pattern used by Eclipse JDT and IntelliJ — `src.zip` is a
/// modular zip whose top-level entries are module directories.
package com.almato.bromo.jdk;
