/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt._
import sbt.Keys._
import sbtassembly.AssemblyPlugin.autoImport._
import java.util.Locale
import java.util.jar.JarFile
import scala.collection.JavaConverters._

/**
 * Shading configuration for native SBT build.
 *
 * This module configures sbt-assembly's ShadeRule for bytecode relocation.
 * Key design decisions:
 *
 * 1. We use `.inAll` for shade rules (not `.inLibrary()`) because:
 *    - Shaded libraries often reference each other (e.g., Jetty uses Guava)
 *    - All references must be consistently relocated across ALL included jars
 *    - Using `.inLibrary(guava)` would miss Guava references in Jetty code
 *
 * 2. We use ModuleID matching (not jar name matching) for jar inclusion because:
 *    - ModuleID matching is more robust than string-based jar name patterns
 *    - It handles version differences and classifier variations correctly
 *    - It's the recommended sbt approach for dependency filtering
 *
 * 3. Assembly inclusion is controlled by `assemblyExcludedJars` setting which
 *    filters the classpath to include only the specific dependencies we want.
 */
object Shading {

  // Package name for relocated/shaded classes
  val sparkShadePackage = "org.sparkproject"

  // ===== MODULE DEFINITIONS =====
  // Define library coordinates for precise dependency matching.
  // These are used by assemblyExcludedJars to filter which jars are included.

  /** Jetty EE10 modules (Jetty 12.x) to include in core assembly */
  private val jettyEe10Modules: Set[(String, String)] = Set(
    ("org.eclipse.jetty.ee10", "jetty-ee10-proxy"),
    ("org.eclipse.jetty.ee10", "jetty-ee10-servlet"),
    ("org.eclipse.jetty.ee10", "jetty-ee10-servlets"),
    ("org.eclipse.jetty.ee10", "jetty-ee10-plus")
  )

  /** Jetty compression modules (Jetty 12.x) */
  private val jettyCompressionModules: Set[(String, String)] = Set(
    ("org.eclipse.jetty.compression", "jetty-compression-server"),
    ("org.eclipse.jetty.compression", "jetty-compression-common"),
    ("org.eclipse.jetty.compression", "jetty-compression-gzip")
  )

  /** Jetty core modules - need version check to exclude old 9.x from avro-ipc-jetty */
  private val jettyCoreModules: Set[(String, String)] = Set(
    ("org.eclipse.jetty", "jetty-io"),
    ("org.eclipse.jetty", "jetty-http"),
    ("org.eclipse.jetty", "jetty-client"),
    ("org.eclipse.jetty", "jetty-util"),
    ("org.eclipse.jetty", "jetty-server"),
    ("org.eclipse.jetty", "jetty-security"),
    ("org.eclipse.jetty", "jetty-session")
  )

  /** Guava modules */
  private val guavaModules: Set[(String, String)] = Set(
    ("com.google.guava", "guava"),
    ("com.google.guava", "failureaccess")
  )

  /** Protobuf modules */
  private val protobufModules: Set[(String, String)] = Set(
    ("com.google.protobuf", "protobuf-java")
  )

  /** All modules to include in core assembly */
  private val coreIncludedModules: Set[(String, String)] =
    jettyEe10Modules ++ jettyCompressionModules ++ jettyCoreModules ++ guavaModules ++ protobufModules

  // Jetty major version prefix from versions.properties (e.g., "12." for version "12.1.5")
  private val jettyMajorVersionPrefix = Versions.jetty.split("\\.")(0) + "."

  // Base merge strategy for assembly
  lazy val baseMergeStrategy: String => sbtassembly.MergeStrategy = {
    case m if m.toLowerCase(Locale.ROOT).endsWith("manifest.mf") => MergeStrategy.discard
    case m if m.toLowerCase(Locale.ROOT).matches("meta-inf.*\\.sf$") => MergeStrategy.discard
    case m if m.toLowerCase(Locale.ROOT).startsWith("meta-inf/services/") => MergeStrategy.filterDistinctLines
    case m if m.toLowerCase(Locale.ROOT).endsWith(".proto") => MergeStrategy.first
    case m if m.toLowerCase(Locale.ROOT).endsWith("module-info.class") => MergeStrategy.discard
    case "log4j2.properties" => MergeStrategy.discard
    case "reference.conf" => MergeStrategy.concat
    case "git.properties" => MergeStrategy.discard
    case "META-INF/io.netty.versions.properties" => MergeStrategy.first
    case m if m.endsWith("Log4j2Plugins.dat") => MergeStrategy.first
    case m if m.endsWith("collect.pro") => MergeStrategy.first
    case _ => MergeStrategy.first
  }

  // Merge strategy for core assembly (backward compatibility)
  lazy val coreMergeStrategy: String => sbtassembly.MergeStrategy = baseMergeStrategy

  /**
   * Check if a jar should be included in core assembly using ModuleID matching.
   * This is more robust than string-based jar name matching.
   *
   * @param jar The attributed jar from the classpath
   * @return true if the jar should be included in the assembly
   */
  private def isCoreIncludedJar(jar: Attributed[File]): Boolean = {
    // Include spark-core jar itself
    jar.get(moduleID.key) match {
      case Some(mod) if mod.organization == "org.apache.spark" && mod.name.startsWith("spark-core") =>
        return true
      case _ => // continue checking
    }

    // Check against our defined module sets using ModuleID
    jar.get(moduleID.key).exists { mod =>
      val orgName = (mod.organization, mod.name)

      // For Jetty core modules, check version to exclude old 9.x from avro-ipc-jetty
      if (jettyCoreModules.contains(orgName)) {
        mod.revision.startsWith(jettyMajorVersionPrefix)
      } else {
        // For other modules, just check org/name match
        coreIncludedModules.contains(orgName)
      }
    }
  }

  /**
   * Fallback jar name matching for jars without ModuleID metadata.
   * This handles edge cases where sbt doesn't attach module information.
   */
  private def isCoreIncludedJarByName(jarName: String): Boolean = {
    // Include spark-core jar
    if (jarName.startsWith("spark-core_")) return true

    // Check Jetty core modules with version filtering
    val jettyCoreNames = jettyCoreModules.map(_._2)
    for (artifact <- jettyCoreNames) {
      if (jarName.startsWith(artifact + "-")) {
        val versionPart = jarName.substring(artifact.length + 1)
        return versionPart.startsWith(jettyMajorVersionPrefix)
      }
    }

    // Check other included modules
    val allNames = coreIncludedModules.map(_._2)
    allNames.exists { artifact =>
      if (jarName.startsWith(artifact)) {
        val suffix = jarName.substring(artifact.length)
        suffix.startsWith("-") && suffix.length > 1 && suffix.charAt(1).isDigit
      } else false
    }
  }

  lazy val coreAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := coreMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    // Only include Jetty, Guava, and Protobuf (matching Maven's artifactSet.includes)
    // Uses ModuleID matching for robustness, with jar name fallback
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filterNot { jar =>
        // Try ModuleID matching first, fall back to jar name matching
        isCoreIncludedJar(jar) || isCoreIncludedJarByName(jar.data.getName)
      }
    },
    // Shade rules matching Maven's relocations
    // Note: We use .inAll because shaded libraries reference each other
    // (e.g., Jetty uses Guava), so ALL references must be relocated consistently
    assembly / assemblyShadeRules := Seq(
      ShadeRule.rename("org.eclipse.jetty.**" -> s"$sparkShadePackage.jetty.@1").inAll,
      ShadeRule.rename("com.google.common.**" -> s"$sparkShadePackage.guava.@1").inAll,
      ShadeRule.rename("com.google.thirdparty.**" -> s"$sparkShadePackage.guava.thirdparty.@1").inAll,
      ShadeRule.rename("com.google.protobuf.**" -> s"$sparkShadePackage.spark_core.protobuf.@1").inAll
    )
  )

  lazy val connectAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := coreMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false)
  )

  // ===== CONNECT CLIENT MODULE DEFINITIONS =====
  // See sql/connect/client/jvm/pom.xml for the canonical list

  /** Connect client included modules by (organization, artifactName) */
  private val connectClientIncludedModules: Set[(String, String)] = Set(
    // com.google.guava:* - Guava and related
    ("com.google.guava", "guava"),
    ("com.google.guava", "failureaccess"),
    ("com.google.guava", "listenablefuture"),
    // com.google.android:* - Android annotations
    ("com.google.android", "annotations"),
    // com.google.api.grpc:* - gRPC protos
    ("com.google.api.grpc", "proto-google-common-protos"),
    // com.google.code.gson:* - JSON
    ("com.google.code.gson", "gson"),
    // com.google.protobuf:* - Protocol Buffers
    ("com.google.protobuf", "protobuf-java"),
    // com.google.flatbuffers:* - FlatBuffers (used by Arrow)
    ("com.google.flatbuffers", "flatbuffers-java"),
    // io.grpc:* - gRPC (only core modules)
    ("io.grpc", "grpc-api"),
    ("io.grpc", "grpc-context"),
    ("io.grpc", "grpc-core"),
    ("io.grpc", "grpc-netty"),
    ("io.grpc", "grpc-protobuf"),
    ("io.grpc", "grpc-protobuf-lite"),
    ("io.grpc", "grpc-stub"),
    ("io.grpc", "grpc-util"),
    ("io.grpc", "grpc-services"),
    ("io.grpc", "grpc-inprocess"),
    // io.netty:* - Netty (exact 12 modules matching Maven's shaded jar)
    ("io.netty", "netty-buffer"),
    ("io.netty", "netty-codec"),
    ("io.netty", "netty-codec-http"),
    ("io.netty", "netty-codec-http2"),
    ("io.netty", "netty-codec-socks"),
    ("io.netty", "netty-common"),
    ("io.netty", "netty-handler"),
    ("io.netty", "netty-handler-proxy"),
    ("io.netty", "netty-resolver"),
    ("io.netty", "netty-transport"),
    ("io.netty", "netty-transport-native-unix-common"),
    // io.perfmark:* - Performance tracing
    ("io.perfmark", "perfmark-api"),
    // org.apache.arrow:* - Arrow
    ("org.apache.arrow", "arrow-format"),
    ("org.apache.arrow", "arrow-memory-core"),
    ("org.apache.arrow", "arrow-memory-netty"),
    ("org.apache.arrow", "arrow-vector"),
    // org.codehaus.mojo:* - Animal sniffer annotations
    ("org.codehaus.mojo", "animal-sniffer-annotations"),
    // org.apache.spark:* - Spark modules (spark-connect-shims intentionally excluded)
    ("org.apache.spark", "spark-connect-client-jvm"),
    ("org.apache.spark", "spark-connect-common"),
    ("org.apache.spark", "spark-sql-api")
  )

  /** Artifact names for fallback jar name matching */
  private val connectClientIncludedArtifactNames: Set[String] =
    connectClientIncludedModules.map(_._2)

  /**
   * Check if a jar should be included in connect-client assembly using ModuleID matching.
   */
  private def isConnectClientIncludedJar(jar: Attributed[File]): Boolean = {
    jar.get(moduleID.key).exists { mod =>
      // Handle Scala cross-versioned artifacts (name might have _2.13 suffix)
      val baseName = mod.name.split("_").head
      connectClientIncludedModules.contains((mod.organization, mod.name)) ||
        connectClientIncludedModules.contains((mod.organization, baseName))
    }
  }

  /**
   * Fallback jar name matching for connect-client assembly.
   * Pattern: artifactId followed by "-" + digit (version) or "_" + digit (scala version)
   */
  private def isConnectClientIncludedJarByName(jarName: String): Boolean = {
    connectClientIncludedArtifactNames.exists { artifact =>
      if (jarName.startsWith(artifact)) {
        val suffix = jarName.substring(artifact.length)
        (suffix.startsWith("-") && suffix.length > 1 && suffix.charAt(1).isDigit) ||
          (suffix.startsWith("_") && suffix.length > 1 && suffix.charAt(1).isDigit)
      } else false
    }
  }

  // Connect client merge strategy - also exclude native libs
  private lazy val connectClientMergeStrategy: String => sbtassembly.MergeStrategy = {
    // Exclude native libraries - they bloat the jar and aren't needed for most use cases
    case m if m.toLowerCase(Locale.ROOT).endsWith(".so") => MergeStrategy.discard
    case m if m.toLowerCase(Locale.ROOT).endsWith(".dylib") => MergeStrategy.discard
    case m if m.toLowerCase(Locale.ROOT).endsWith(".dll") => MergeStrategy.discard
    case m if m.toLowerCase(Locale.ROOT).endsWith(".jnilib") => MergeStrategy.discard
    case m => coreMergeStrategy(m)
  }

  lazy val connectClientAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := connectClientMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    // Match Maven's artifactSet.includes - only include specific dependencies
    // Uses ModuleID matching for robustness, with jar name fallback
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filterNot { jar =>
        isConnectClientIncludedJar(jar) || isConnectClientIncludedJarByName(jar.data.getName)
      }
    },
    // Shade rules to match Maven's relocations (order matters - more specific rules first)
    // Note: We use .inAll because shaded libraries reference each other
    // (e.g., gRPC uses Netty, Arrow uses Netty), so ALL references must be relocated
    assembly / assemblyShadeRules := Seq(
      // Guava gets special treatment - shaded to connect.guava
      ShadeRule.rename("com.google.common.**" -> s"$sparkShadePackage.connect.guava.@1").inAll,
      // Other com.google packages
      ShadeRule.rename("com.google.**" -> s"$sparkShadePackage.com.google.@1").inAll,
      ShadeRule.rename("io.grpc.**" -> s"$sparkShadePackage.io.grpc.@1").inAll,
      ShadeRule.rename("io.netty.**" -> s"$sparkShadePackage.io.netty.@1").inAll,
      ShadeRule.rename("io.perfmark.**" -> s"$sparkShadePackage.io.perfmark.@1").inAll,
      ShadeRule.rename("org.codehaus.**" -> s"$sparkShadePackage.org.codehaus.@1").inAll,
      ShadeRule.rename("org.apache.arrow.**" -> s"$sparkShadePackage.org.apache.arrow.@1").inAll,
      ShadeRule.rename("android.annotation.**" -> s"$sparkShadePackage.android.annotation.@1").inAll
    )
  )

  lazy val protobufAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := coreMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false)
  )

  // Kafka assembly excluded jar prefixes (matching Maven's provided scope + transitive deps)
  // Similar to kinesis but with Kafka-specific inclusions
  private val kafkaExcludedPrefixes = Set(
    // Spark modules (all of them for Kafka - it's a small assembly)
    "spark-core", "spark-streaming_", "spark-tags", "spark-unsafe", "spark-launcher",
    "spark-network-common", "spark-network-shuffle", "spark-common-utils", "spark-localdb",
    "spark-kvstore", "spark-variant",
    // Scala library
    "scala-library", "scala-reflect", "scala-compiler",
    // Commons
    "commons-codec", "commons-lang-2", "commons-io", "commons-text", "commons-collections",
    "commons-math", "commons-net", "commons-pool", "commons-logging", "commons-crypto",
    // Protobuf (provided in Kafka assembly pom)
    "protobuf-java",
    // LZ4 (provided)
    "lz4-java",
    // Hadoop
    "hadoop-",
    // Avro
    "avro-",
    // Curator and ZooKeeper
    "curator-", "zookeeper-",
    // Logging
    "log4j-", "slf4j-",
    // Snappy
    "snappy-java",
    // Jersey and JAX-RS
    "jersey-", "hk2-", "aopalliance", "javassist", "osgi-resource-locator",
    "jakarta.", "javax.",
    // Web UI related
    "jetty-",
    // Large transitive deps from Spark core
    "fastutil-", // it.unimi.dsi:fastutil
    "netty-", // Netty
    "guava-", "listenablefuture", "failureaccess", // Guava
    "gson", // Google Gson
    "jsr305", "checker-qual", "error_prone_annotations", "j2objc-annotations",
    "jna", "jna-platform",
    // Other Spark deps
    "kryo", "minlog", "objenesis", // Kryo
    "chill", "paranamer", // Twitter Chill
    "json4s-", "jackson-module-scala", // JSON
    "icu4j", "compress-lzf",
    "RoaringBitmap", "shims",
    "rocksdbjni", "leveldbjni", "leveldb",
    "py4j", "jline", "janino", "commons-compiler",
    "metrics-core", "metrics-jmx", "metrics-json", "metrics-jvm", "metrics-graphite",
    "ivy-", "oro-", "velocity-", "xbean-",
    "audience-annotations", "activation", "annotation-api", "jaxb", "stax",
    "unused-1"
  )

  // Check if a jar should be excluded from kafka assembly
  private def isKafkaExcludedJar(jarName: String): Boolean = {
    kafkaExcludedPrefixes.exists(prefix => jarName.startsWith(prefix))
  }

  // Kafka assembly settings - exclude jars matching provided scope
  lazy val kafkaAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := baseMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter(jar => isKafkaExcludedJar(jar.data.getName))
    }
  )

  // Kinesis assembly excluded jar prefixes (matching Maven's provided scope + transitive deps)
  // These jars are already in the Spark assembly or are excluded from the connector assembly
  private val kinesisExcludedPrefixes = Set(
    // Spark modules (except kinesis-asl itself)
    "spark-core", "spark-streaming_", "spark-tags", "spark-unsafe", "spark-launcher",
    "spark-network-common", "spark-network-shuffle", "spark-common-utils", "spark-localdb",
    "spark-kvstore", "spark-variant",
    // Scala
    "scala-library", "scala-reflect", "scala-compiler",
    // Jackson databind (provided, but NOT jackson-core/annotations)
    "jackson-databind",
    // Commons lang (v2, not v3)
    "commons-lang-2",
    // Jersey and related
    "jersey-", "hk2-", "aopalliance", "mimepull", "javassist", "osgi-resource-locator",
    // Log4j and logging
    "log4j-", "slf4j-",
    // Hadoop
    "hadoop-",
    // Avro
    "avro-",
    // Curator and ZooKeeper
    "curator-", "zookeeper-",
    // Snappy
    "snappy-java",
    // Additional transitive deps from Spark core that should be excluded
    "py4j", "jline", "janino", "commons-compiler",
    "metrics-core", "metrics-jmx", "metrics-json", "metrics-jvm", "metrics-graphite",
    "RoaringBitmap", "shims",
    "rocksdbjni",
    "leveldbjni", "leveldb",
    "unused-1",
    // Web UI related
    "jakarta.", "javax.",
    // Guava (Spark's shaded)
    "guava-",
    // Spark core transitive deps
    "stream-", // com.clearspring.analytics:stream
    "kryo", "minlog", // com.esotericsoftware:kryo
    "icu4j", // com.ibm.icu:icu4j
    "compress-lzf", // com.ning:compress-lzf
    "paranamer", // com.thoughtworks.paranamer
    "chill", // com.twitter:chill
    "json4s-", // org.json4s
    "jctools-", // org.jctools
    "jspecify", // org.jspecify
    "objenesis", // org.objenesis
    // Other Spark deps
    "commons-crypto", "commons-text", "commons-io", "commons-collections",
    "commons-math", "commons-net", "commons-pool", "commons-logging",
    "ivy-", "oro-", "velocity-", "xbean-",
    "audience-annotations", // org.apache.yetus
    "jetty-", // jetty already shaded in core
    "activation", "annotation-api", "jaxb", "stax",
    // Large transitive deps from Spark core
    "fastutil-", // it.unimi.dsi:fastutil (huge library)
    "netty-tcnative", "netty-codec-native-quic", // native SSL libs
    "jna", "jna-platform", // JNA (Spark core)
    "gson", // Spark core uses shaded Guava instead
    "jsr305", // findbugs annotations
    "checker-qual", // Checker framework
    "error_prone_annotations", // Google error-prone
    "j2objc-annotations", // J2ObjC
    "listenablefuture", // Guava's ListenableFuture
    "failureaccess", // Guava internal
    // Additional exclusions found by comparing with Maven
    "tink-", // com.google.crypto.tink
    "protobuf-java", // com.google.protobuf (not needed for kinesis)
    "guava", // com.google.common (Guava)
    "roaringbitmap", // org.roaringbitmap
    "lz4-java", // net.jpountz.lz4
    "pickle", // net.razorvine.pickle (py4j related)
    "scala-xml", // scala-xml (not needed)
    // Netty exclusions - use individual modules, not netty-all uber jar
    "netty-all", // uber jar with all netty modules
    "netty-transport-native", // native transport (epoll, kqueue, io_uring)
    "netty-transport-classes", // transport classes for native
    "netty-resolver-dns", // DNS resolver
    "netty-codec-dns", // DNS codec
    "netty-codec-haproxy", "netty-codec-memcache", "netty-codec-mqtt",
    "netty-codec-redis", "netty-codec-smtp", "netty-codec-stomp",
    "netty-codec-xml", "netty-codec-protobuf", "netty-codec-marshalling",
    "netty-transport-rxtx", "netty-transport-sctp", "netty-transport-udt",
    "netty-handler-ssl-ocsp"
  )

  // Check if a jar should be excluded from kinesis assembly
  private def isKinesisExcludedJar(jarName: String): Boolean = {
    kinesisExcludedPrefixes.exists(prefix => jarName.startsWith(prefix))
  }

  // Kinesis assembly settings - exclude jars matching provided scope
  lazy val kinesisAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := baseMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter(jar => isKinesisExcludedJar(jar.data.getName))
    }
  )

  // Generic assembly settings for connector assemblies (deprecated - use specific settings)
  lazy val connectorAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := baseMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false)
  )

  // Main assembly module settings - exclude connect-shims stub classes
  lazy val mainAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := {
      // Exclude connect-shims stub classes - they conflict with real implementations
      case m if m.startsWith("org/apache/spark/") && isConnectShimsClass(m) => MergeStrategy.discard
      case m => baseMergeStrategy(m)
    },
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    // Exclude the connect-shims jar entirely from assembly
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter(_.data.getName.contains("connect-shims"))
    }
  )

  // Classes that are stubs in connect-shims and should be excluded from main assembly
  private def isConnectShimsClass(path: String): Boolean = {
    val shimClasses = Set(
      "org/apache/spark/SparkConf",
      "org/apache/spark/SparkContext",
      "org/apache/spark/sql/sources/BaseRelation",
      "org/apache/spark/sql/util/ExecutionListenerManager",
      "org/apache/spark/sql/SparkSessionExtensions",
      "org/apache/spark/sql/catalyst/analysis/Analyzer",
      "org/apache/spark/sql/execution/QueryExecution",
      "org/apache/spark/sql/internal/SessionState"
    )
    shimClasses.exists(c => path.startsWith(c))
  }

  // ===== SHADING VERIFICATION =====

  /** Task key for verifying shading was applied correctly */
  val verifyShading = taskKey[Unit]("Verify shading relocations are correct in the assembly jar")

  /**
   * Patterns that should NOT exist in a correctly shaded assembly.
   * If any of these are found, shading failed.
   */
  private val coreForbiddenPatterns = Seq(
    "org/eclipse/jetty/",      // Should be relocated to org/sparkproject/jetty/
    "com/google/common/",      // Should be relocated to org/sparkproject/guava/
    "com/google/protobuf/"     // Should be relocated to org/sparkproject/spark_core/protobuf/
  )

  private val connectClientForbiddenPatterns = Seq(
    "com/google/common/",      // Should be relocated to org/sparkproject/connect/guava/
    "com/google/protobuf/",    // Should be relocated to org/sparkproject/com/google/protobuf/
    "io/grpc/",                // Should be relocated to org/sparkproject/io/grpc/
    "io/netty/",               // Should be relocated to org/sparkproject/io/netty/
    "org/apache/arrow/"        // Should be relocated to org/sparkproject/org/apache/arrow/
  )

  /**
   * Verify that an assembly jar has been correctly shaded.
   * Checks that forbidden patterns don't exist as class paths.
   *
   * @param jarFile The assembly jar to verify
   * @param forbiddenPatterns Patterns that should not exist
   * @param log Logger for output
   * @return List of violations found (empty if verification passed)
   */
  def verifyShadingInJar(
      jarFile: File,
      forbiddenPatterns: Seq[String],
      log: sbt.util.Logger): Seq[String] = {
    if (!jarFile.exists()) {
      log.warn(s"Assembly jar not found: ${jarFile.getAbsolutePath}")
      return Seq(s"Jar not found: $jarFile")
    }

    val jar = new JarFile(jarFile)
    try {
      val entries = jar.entries().asScala.toList
      val classEntries = entries.filter(_.getName.endsWith(".class"))

      val violations = forbiddenPatterns.flatMap { pattern =>
        val matches = classEntries.filter(_.getName.startsWith(pattern))
        if (matches.nonEmpty) {
          log.error(s"Found ${matches.size} unshaded classes matching '$pattern'")
          matches.take(5).foreach(e => log.error(s"  - ${e.getName}"))
          if (matches.size > 5) log.error(s"  ... and ${matches.size - 5} more")
          Some(s"Pattern '$pattern': ${matches.size} unshaded classes")
        } else {
          log.info(s"✓ No unshaded classes matching '$pattern'")
          None
        }
      }

      if (violations.isEmpty) {
        log.success(s"Shading verification passed for ${jarFile.getName}")
      } else {
        log.error(s"Shading verification FAILED for ${jarFile.getName}")
      }

      violations
    } finally {
      jar.close()
    }
  }

  /**
   * Settings to add shading verification task to a project.
   * Usage: Add `Shading.verifyShadingSettings(Shading.coreForbiddenPatterns)` to a project.
   */
  def verifyShadingSettings(forbiddenPatterns: Seq[String]): Seq[Setting[_]] = Seq(
    verifyShading := {
      val log = streams.value.log
      val jarFile = (assembly / assemblyOutputPath).value
      val violations = verifyShadingInJar(jarFile, forbiddenPatterns, log)
      if (violations.nonEmpty) {
        sys.error(s"Shading verification failed with ${violations.size} violations")
      }
    }
  )

  /** Verification settings for core assembly */
  lazy val coreVerifyShadingSettings: Seq[Setting[_]] =
    verifyShadingSettings(coreForbiddenPatterns)

  /** Verification settings for connect-client assembly */
  lazy val connectClientVerifyShadingSettings: Seq[Setting[_]] =
    verifyShadingSettings(connectClientForbiddenPatterns)

  // ===== DEBUG LOGGING =====

  /**
   * Settings to enable debug logging for assembly.
   * Useful for troubleshooting shading issues.
   * Usage: Add `Shading.debugAssemblySettings` to a project when debugging.
   */
  lazy val debugAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / logLevel := Level.Debug
  )
}
