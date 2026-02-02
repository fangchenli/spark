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

  lazy val coreAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := coreMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false),
    // Shade rules are configured in build.sbt using ShadeRule from autoImport
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

  // Generic assembly settings for connector assemblies (kafka, kinesis, etc.)
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
