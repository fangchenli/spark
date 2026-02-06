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
   * Scala binary version suffix for the current build (e.g., "_2.13").
   * Used to strip cross-version suffixes from artifact names when comparing ModuleIDs.
   */
  private val scalaBinarySuffix: String = "_" + Versions.scalaBinary

  /**
   * Check if a jar should be included in connect-client assembly using ModuleID matching.
   *
   * Note on cross-versioning: When dependencies are declared with %% (e.g., "org" %% "name" % "ver"),
   * sbt's ModuleID.name typically contains the base name without Scala suffix. However, some edge
   * cases may have the suffix in the name, so we check both the original name and strip the
   * current Scala binary version suffix if present.
   */
  private def isConnectClientIncludedJar(jar: Attributed[File]): Boolean = {
    jar.get(moduleID.key).exists { mod =>
      // First, try exact match (works for most %% dependencies where name is already base name)
      if (connectClientIncludedModules.contains((mod.organization, mod.name))) {
        true
      } else {
        // Handle edge case: strip Scala binary version suffix if present
        val baseName = if (mod.name.endsWith(scalaBinarySuffix)) {
          mod.name.dropRight(scalaBinarySuffix.length)
        } else {
          mod.name
        }
        connectClientIncludedModules.contains((mod.organization, baseName))
      }
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

  // ===== KAFKA ASSEMBLY EXCLUSIONS =====
  // Organizations to completely exclude from Kafka assembly
  private val kafkaExcludedOrganizations = Set(
    "org.apache.spark",           // All Spark modules
    "org.scala-lang",             // Scala library
    "org.apache.hadoop",          // Hadoop
    "org.apache.avro",            // Avro
    "org.apache.curator",         // Curator
    "org.apache.zookeeper",       // ZooKeeper
    "org.apache.logging.log4j",   // Log4j
    "org.slf4j",                  // SLF4J
    "org.eclipse.jetty",          // Jetty (all variants)
    "org.eclipse.jetty.ee10",
    "org.eclipse.jetty.compression",
    "io.netty",                   // Netty
    "com.google.guava",           // Guava
    "com.google.protobuf",        // Protobuf
    "it.unimi.dsi",               // fastutil
    "net.java.dev.jna",           // JNA
    "com.esotericsoftware",       // Kryo
    "com.twitter",                // Chill
    "org.json4s",                 // JSON4s
    "io.dropwizard.metrics",      // Metrics
    "org.glassfish.jersey",       // Jersey
    "org.glassfish.hk2"           // HK2
  )

  // Artifact prefixes to exclude (for orgs not fully excluded)
  private val kafkaExcludedArtifactPrefixes = Set(
    "commons-codec", "commons-lang-2", "commons-io", "commons-text", "commons-collections",
    "commons-math", "commons-net", "commons-pool", "commons-logging", "commons-crypto",
    "lz4-java", "snappy-java", "aopalliance", "javassist", "osgi-resource-locator",
    "jakarta.", "javax.", "gson", "jsr305", "checker-qual", "error_prone_annotations",
    "j2objc-annotations", "minlog", "objenesis", "paranamer", "jackson-module-scala",
    "icu4j", "compress-lzf", "RoaringBitmap", "shims", "rocksdbjni", "leveldbjni",
    "leveldb", "py4j", "jline", "janino", "commons-compiler", "ivy-", "oro-",
    "velocity-", "xbean-", "audience-annotations", "activation", "annotation-api",
    "jaxb", "stax", "unused-1", "listenablefuture", "failureaccess"
  )

  /**
   * Check if a jar should be excluded from Kafka assembly using ModuleID matching.
   */
  private def isKafkaExcludedJar(jar: Attributed[File]): Boolean = {
    jar.get(moduleID.key) match {
      case Some(mod) =>
        // Check organization-level exclusion
        kafkaExcludedOrganizations.contains(mod.organization) ||
          // Check artifact prefix exclusion
          kafkaExcludedArtifactPrefixes.exists(prefix => mod.name.startsWith(prefix))
      case None =>
        // Fallback to jar name matching
        isKafkaExcludedJarByName(jar.data.getName)
    }
  }

  /** Fallback jar name matching for Kafka assembly */
  private def isKafkaExcludedJarByName(jarName: String): Boolean = {
    kafkaExcludedArtifactPrefixes.exists(prefix => jarName.startsWith(prefix)) ||
      jarName.startsWith("spark-") || jarName.startsWith("scala-") ||
      jarName.startsWith("hadoop-") || jarName.startsWith("avro-") ||
      jarName.startsWith("curator-") || jarName.startsWith("zookeeper-") ||
      jarName.startsWith("log4j-") || jarName.startsWith("slf4j-") ||
      jarName.startsWith("jetty-") || jarName.startsWith("netty-") ||
      jarName.startsWith("guava-") || jarName.startsWith("protobuf-") ||
      jarName.startsWith("fastutil-") || jarName.startsWith("jna-") ||
      jarName.startsWith("kryo-") || jarName.startsWith("chill-") ||
      jarName.startsWith("json4s-") || jarName.startsWith("metrics-") ||
      jarName.startsWith("jersey-") || jarName.startsWith("hk2-")
  }

  // Kafka assembly settings - exclude jars matching provided scope
  lazy val kafkaAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := baseMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter(jar => isKafkaExcludedJar(jar))
    }
  )

  // ===== KINESIS ASSEMBLY EXCLUSIONS =====
  // Organizations to completely exclude from Kinesis assembly
  private val kinesisExcludedOrganizations = Set(
    "org.apache.spark",           // All Spark modules (except kinesis-asl itself, handled separately)
    "org.scala-lang",             // Scala library
    "org.apache.hadoop",          // Hadoop
    "org.apache.avro",            // Avro
    "org.apache.curator",         // Curator
    "org.apache.zookeeper",       // ZooKeeper
    "org.apache.logging.log4j",   // Log4j
    "org.slf4j",                  // SLF4J
    "org.eclipse.jetty",          // Jetty (all variants)
    "org.eclipse.jetty.ee10",
    "org.eclipse.jetty.compression",
    "com.google.guava",           // Guava
    "com.google.protobuf",        // Protobuf
    "com.google.crypto.tink",     // Tink
    "it.unimi.dsi",               // fastutil
    "net.java.dev.jna",           // JNA
    "com.esotericsoftware",       // Kryo
    "com.twitter",                // Chill
    "org.json4s",                 // JSON4s
    "io.dropwizard.metrics",      // Metrics
    "org.glassfish.jersey",       // Jersey
    "org.glassfish.hk2",          // HK2
    "com.clearspring.analytics",  // Stream
    "com.ibm.icu",                // ICU4J
    "com.ning",                   // compress-lzf
    "com.thoughtworks.paranamer", // Paranamer
    "org.jctools",                // JCTools
    "org.jspecify",               // JSpecify
    "org.objenesis",              // Objenesis
    "org.roaringbitmap",          // RoaringBitmap
    "net.jpountz.lz4",            // LZ4
    "net.razorvine"               // Pickle (py4j related)
  )

  // Kinesis-specific excluded artifact prefixes (for orgs not fully excluded)
  private val kinesisExcludedArtifactPrefixes = Set(
    // Jackson (only databind, not core/annotations)
    "jackson-databind",
    // Commons
    "commons-lang-2", "commons-crypto", "commons-text", "commons-io",
    "commons-collections", "commons-math", "commons-net", "commons-pool",
    "commons-logging", "commons-compiler",
    // Jersey/JAX-RS related
    "aopalliance", "mimepull", "javassist", "osgi-resource-locator",
    "jakarta.", "javax.",
    // Other
    "snappy-java", "py4j", "jline", "janino", "RoaringBitmap", "shims",
    "rocksdbjni", "leveldbjni", "leveldb", "unused-1", "ivy-", "oro-",
    "velocity-", "xbean-", "audience-annotations", "activation",
    "annotation-api", "jaxb", "stax", "gson", "jsr305", "checker-qual",
    "error_prone_annotations", "j2objc-annotations", "listenablefuture",
    "failureaccess", "scala-xml", "minlog",
    // Netty native/extras (exclude specific modules, not all netty)
    "netty-all", "netty-transport-native", "netty-transport-classes",
    "netty-resolver-dns", "netty-codec-dns", "netty-codec-haproxy",
    "netty-codec-memcache", "netty-codec-mqtt", "netty-codec-redis",
    "netty-codec-smtp", "netty-codec-stomp", "netty-codec-xml",
    "netty-codec-protobuf", "netty-codec-marshalling", "netty-transport-rxtx",
    "netty-transport-sctp", "netty-transport-udt", "netty-handler-ssl-ocsp",
    "netty-tcnative", "netty-codec-native-quic"
  )

  /**
   * Check if a jar should be excluded from Kinesis assembly using ModuleID matching.
   * Note: kinesis-asl itself is NOT excluded even though org.apache.spark is in excluded orgs.
   */
  private def isKinesisExcludedJar(jar: Attributed[File]): Boolean = {
    jar.get(moduleID.key) match {
      case Some(mod) =>
        // Don't exclude kinesis-asl itself
        if (mod.organization == "org.apache.spark" && mod.name.contains("kinesis")) {
          false
        } else {
          // Check organization-level exclusion
          kinesisExcludedOrganizations.contains(mod.organization) ||
            // Check artifact prefix exclusion
            kinesisExcludedArtifactPrefixes.exists(prefix => mod.name.startsWith(prefix))
        }
      case None =>
        // Fallback to jar name matching
        isKinesisExcludedJarByName(jar.data.getName)
    }
  }

  /** Fallback jar name matching for Kinesis assembly */
  private def isKinesisExcludedJarByName(jarName: String): Boolean = {
    // Don't exclude kinesis-asl itself
    if (jarName.contains("kinesis")) return false

    kinesisExcludedArtifactPrefixes.exists(prefix => jarName.startsWith(prefix)) ||
      jarName.startsWith("spark-") || jarName.startsWith("scala-") ||
      jarName.startsWith("hadoop-") || jarName.startsWith("avro-") ||
      jarName.startsWith("curator-") || jarName.startsWith("zookeeper-") ||
      jarName.startsWith("log4j-") || jarName.startsWith("slf4j-") ||
      jarName.startsWith("jetty-") || jarName.startsWith("guava-") ||
      jarName.startsWith("protobuf-") || jarName.startsWith("tink-") ||
      jarName.startsWith("fastutil-") || jarName.startsWith("jna-") ||
      jarName.startsWith("kryo-") || jarName.startsWith("chill-") ||
      jarName.startsWith("json4s-") || jarName.startsWith("metrics-") ||
      jarName.startsWith("jersey-") || jarName.startsWith("hk2-") ||
      jarName.startsWith("stream-") || jarName.startsWith("icu4j-") ||
      jarName.startsWith("compress-lzf-") || jarName.startsWith("paranamer-") ||
      jarName.startsWith("jctools-") || jarName.startsWith("objenesis-") ||
      jarName.startsWith("roaringbitmap-") || jarName.startsWith("lz4-") ||
      jarName.startsWith("pickle-")
  }

  // Kinesis assembly settings - exclude jars matching provided scope
  lazy val kinesisAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := baseMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter(jar => isKinesisExcludedJar(jar))
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
   * These are public so users can combine them with custom patterns if needed.
   */
  val coreForbiddenPatterns: Seq[String] = Seq(
    "org/eclipse/jetty/",      // Should be relocated to org/sparkproject/jetty/
    "com/google/common/",      // Should be relocated to org/sparkproject/guava/
    "com/google/protobuf/"     // Should be relocated to org/sparkproject/spark_core/protobuf/
  )

  val connectClientForbiddenPatterns: Seq[String] = Seq(
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
          log.info(s"[OK] No unshaded classes matching '$pattern'")
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
