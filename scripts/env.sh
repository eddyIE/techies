# Source before any Maven command:  source scripts/env.sh
#
# Why this file exists: this machine has two JDKs. An unversioned Homebrew `openjdk`
# (Java 27) shadows `openjdk@21`, which is keg-only. Plain `mvn` therefore picks Java 27,
# and `/usr/libexec/java_home -v 21` also returns the 27 JDK because the keg-only JDK is
# not registered in /Library/Java/JavaVirtualMachines. Without this export the project
# compiles against the wrong JDK.
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
