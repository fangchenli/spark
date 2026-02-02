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

/**
 * Shading configuration for native SBT build.
 * Note: Full shading configuration uses sbt-assembly's ShadeRule which needs
 * to be configured in build.sbt where the assembly plugin is properly loaded.
 */
object Shading {

  // Package name for relocated/shaded classes
  val sparkShadePackage = "org.sparkproject"

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

  // Artifacts to include in core assembly (matches Maven's shade plugin artifactSet.includes)
  // See core/pom.xml for the canonical list
  // Note: We specify exact artifact names without version-based patterns for Jetty
  // because avro-ipc-jetty pulls in old Jetty 9.x which we need to exclude
  private val coreIncludedArtifacts = Set(
    // org.eclipse.jetty.ee10:* - Jetty EE10 modules (Jetty 12.x)
    "jetty-ee10-proxy", "jetty-ee10-servlet", "jetty-ee10-servlets", "jetty-ee10-plus",
    // org.eclipse.jetty.compression:* - Jetty compression (Jetty 12.x)
    "jetty-compression-server", "jetty-compression-common", "jetty-compression-gzip",
    // com.google.protobuf:* - Protocol Buffers
    "protobuf-java"
  )

  // Jetty major version prefix from versions.properties (e.g., "12." for version "12.1.5")
  private val jettyMajorVersionPrefix = Versions.jetty.split("\\.")(0) + "."

  // Jetty core modules - these need version check to exclude old Jetty 9.x from avro-ipc-jetty
  private val jettyCoreArtifacts = Set(
    "jetty-io", "jetty-http", "jetty-client", "jetty-util", "jetty-server",
    "jetty-security", "jetty-session"
  )

  // Match jar names for core assembly inclusion
  // Includes: specific dependency artifacts, plus only spark-core jar
  private def isCoreIncludedJar(jarName: String): Boolean = {
    // Include only spark-core jar (not other Spark modules like network-common, unsafe, etc.)
    // Pattern: spark-core_2.13-VERSION.jar or spark-core_2.12-VERSION.jar
    if (jarName.startsWith("spark-core_")) {
      return true
    }

    // Check Jetty core modules - must match configured version, not old 9.x from avro-ipc-jetty
    for (artifact <- jettyCoreArtifacts) {
      if (jarName.startsWith(artifact + "-")) {
        val versionPart = jarName.substring(artifact.length + 1) // skip artifact name and hyphen
        if (versionPart.startsWith(jettyMajorVersionPrefix)) {
          return true
        }
        // If it's a different major version, don't include
        return false
      }
    }

    // Include specific dependency artifacts (non-Jetty or Jetty 12.x only modules)
    coreIncludedArtifacts.exists { artifact =>
      if (jarName.startsWith(artifact)) {
        val suffix = jarName.substring(artifact.length)
        // After artifact name, expect: -VERSION (starts with digit)
        suffix.startsWith("-") && suffix.length > 1 && suffix.charAt(1).isDigit
      } else false
    }
  }

  lazy val coreAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := coreMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    // Only include Jetty, Guava, and Protobuf (matching Maven's artifactSet.includes)
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filterNot(jar => isCoreIncludedJar(jar.data.getName))
    },
    // Shade rules matching Maven's relocations
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

  // Exact artifact names to include in connect-client assembly (matches Maven's artifactSet.includes)
  // See sql/connect/client/jvm/pom.xml for the canonical list
  // Jar names follow pattern: {artifactId}-{version}.jar or {artifactId}_{scalaVersion}-{version}.jar
  private val connectClientIncludedArtifacts = Set(
    // com.google.guava:* - Guava and related
    "guava", "failureaccess", "listenablefuture",
    // com.google.android:* - Android annotations
    "annotations",
    // com.google.api.grpc:* - gRPC protos
    "proto-google-common-protos",
    // com.google.code.gson:* - JSON
    "gson",
    // com.google.protobuf:* - Protocol Buffers
    "protobuf-java",
    // com.google.flatbuffers:* - FlatBuffers (used by Arrow)
    "flatbuffers-java",
    // io.grpc:* - gRPC (only core modules)
    "grpc-api", "grpc-context", "grpc-core", "grpc-netty",
    "grpc-protobuf", "grpc-protobuf-lite", "grpc-stub", "grpc-util",
    "grpc-services", "grpc-inprocess",
    // io.netty:* - Netty (exact 12 modules matching Maven's shaded jar)
    "netty-buffer", "netty-codec-base", "netty-codec-compression",
    "netty-codec-http", "netty-codec-http2", "netty-codec-socks",
    "netty-common", "netty-handler", "netty-handler-proxy",
    "netty-resolver", "netty-transport", "netty-transport-native-unix-common",
    // io.perfmark:* - Performance tracing
    "perfmark-api",
    // org.apache.arrow:* - Arrow
    "arrow-format", "arrow-memory-core", "arrow-memory-netty", "arrow-vector",
    // org.codehaus.mojo:* - Animal sniffer annotations
    "animal-sniffer-annotations",
    // org.apache.spark:* - Spark modules included in assembly (matches Maven's artifactSet)
    // Note: spark-connect-shims is intentionally excluded (same as Maven)
    "spark-connect-client-jvm", "spark-connect-common", "spark-sql-api"
  )

  // Match jar names like "netty-transport-4.2.9.Final.jar" but not "netty-transport-classes-epoll-4.2.9.Final.jar"
  // Pattern: artifactId followed by "-" + digit (version) or "_" + digit (scala version)
  private def isConnectClientIncludedJar(jarName: String): Boolean = {
    connectClientIncludedArtifacts.exists { artifact =>
      if (jarName.startsWith(artifact)) {
        val suffix = jarName.substring(artifact.length)
        // After artifact name, expect: -VERSION or _SCALA-VERSION
        suffix.startsWith("-") && suffix.length > 1 && suffix.charAt(1).isDigit ||
        suffix.startsWith("_") && suffix.length > 1 && suffix.charAt(1).isDigit
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
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filterNot(jar => isConnectClientIncludedJar(jar.data.getName))
    },
    // Shade rules to match Maven's relocations (order matters - more specific rules first)
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
}
