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

import Dependencies._
import Settings._
import com.simplytyped.Antlr4Plugin._
import sbtprotoc.ProtocPlugin.autoImport._

// ===== GLOBAL SETTINGS =====
ThisBuild / organization := "org.apache.spark"
ThisBuild / version := Versions.spark
ThisBuild / scalaVersion := Versions.scala

// ===== ROOT PROJECT =====
lazy val spark = (project in file("."))
  .aggregate(
    // Tier 1: No Spark dependencies
    tags, sketch, variant,
    // Tier 2: Common utilities
    commonUtilsJava, commonUtils,
    // Tier 3: Infrastructure
    unsafe, kvstore, networkCommon, networkShuffle, launcher,
    // Tier 4: SQL API
    connectShims, sqlApi,
    // Tier 5: Core
    core,
    // Tier 6: SQL Engine
    catalyst, sql, pipelines,
    // Tier 7: Streaming & ML
    streaming, mlLibLocal, graphx, mllib,
    // Tier 8: Hive
    hive, hiveThriftserver,
    // Tier 9: REPL
    repl,
    // Tier 10: Connectors
    avro, protobuf, hadoopCloud, profiler,
    kafkaTokenProvider, kafkaStreaming, kafkaSql,
    kinesisAsl, gangliaLgpl,
    // Tier 11: Resource Managers
    networkYarn, yarn, kubernetes,
    // Tier 12: Spark Connect
    connectCommon, connect, connectClientJvm, connectClientJdbc,
    // Tier 13: Tools & Examples
    tools, examples,
    // Tier 14: Assembly (packaging)
    assembly, kafkaAssembly, kinesisAslAssembly,
    // Tier 15: Integration Tests
    dockerIntegrationTests, kubernetesIntegrationTests
  )
  .settings(
    name := "spark-parent",
    publish / skip := true,
    // Don't compile or test the root project
    Compile / sources := Seq.empty,
    Test / sources := Seq.empty
  )

// =============================================================================
// TIER 1: NO SPARK DEPENDENCIES
// =============================================================================

lazy val tags = (project in file("common/tags"))
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-tags",
    libraryDependencies ++= Seq(
      Scala.library
    ) ++ TestDeps.common
  )

lazy val sketch = (project in file("common/sketch"))
  .dependsOn(tags % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-sketch",
    libraryDependencies ++= Seq(
      Misc.datasketches
    ) ++ TestDeps.common
  )

lazy val variant = (project in file("common/variant"))
  .dependsOn(tags, commonUtils, tags % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-variant",
    libraryDependencies ++= Seq(
      Jackson.core,
      Jackson.databind,
      Google.jsr305
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 2: COMMON UTILITIES
// =============================================================================

lazy val commonUtilsJava = (project in file("common/utils-java"))
  .dependsOn(tags, tags % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-common-utils-java",
    libraryDependencies ++= Seq(
      Jackson.databind,
      Google.guava,
      Database.rocksdb,
      Database.leveldbjni
    ) ++ Netty.allWithNatives ++ Logging.all ++ TestDeps.common
  )

lazy val commonUtils = (project in file("common/utils"))
  .dependsOn(commonUtilsJava, tags % "test->test", commonUtilsJava % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-common-utils",
    libraryDependencies ++= Seq(
      CodeGen.xbeanAsm,
      Jackson.databind,
      Jackson.moduleScala,
      Misc.ivy,
      Misc.oro
    ) ++ Logging.all ++ TestDeps.common,
    // Generate spark-version-info.properties
    Compile / resourceGenerators += Def.task {
      val file = (Compile / resourceManaged).value / "spark-version-info.properties"
      val gitRevision = scala.util.Try {
        scala.sys.process.Process("git rev-parse HEAD").!!.trim
      }.getOrElse("unknown")
      val gitBranch = scala.util.Try {
        scala.sys.process.Process("git rev-parse --abbrev-ref HEAD").!!.trim
      }.getOrElse("unknown")
      val gitUrl = scala.util.Try {
        scala.sys.process.Process("git config --get remote.origin.url").!!.trim
      }.getOrElse("unknown")
      val content = s"""version=${Versions.spark}
                       |user=${System.getProperty("user.name")}
                       |revision=$gitRevision
                       |branch=$gitBranch
                       |date=${java.time.Instant.now()}
                       |url=$gitUrl
                       |docroot=https://spark.apache.org/docs/latest
                       |""".stripMargin
      IO.write(file, content)
      Seq(file)
    }.taskValue
  )

// =============================================================================
// TIER 3: INFRASTRUCTURE
// =============================================================================

lazy val unsafe = (project in file("common/unsafe"))
  .dependsOn(commonUtils, variant, tags % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-unsafe",
    libraryDependencies ++= Seq(
      Misc.icu4j,
      Google.jsr305,
      Kryo.shaded,
      Kryo.chill,
      Kryo.chillJava,
      Kryo.objenesis,
      Logging.slf4jApi % Provided
    ) ++ TestDeps.common
  )

lazy val kvstore = (project in file("common/kvstore"))
  .dependsOn(commonUtils, tags % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-kvstore",
    libraryDependencies ++= Seq(
      Google.guava,
      Database.leveldbjni,
      Database.rocksdb,
      Jackson.core,
      Jackson.databind,
      Jackson.annotations,
      Logging.slf4jApi,
      Metrics.core % sbt.Test
    ) ++ TestDeps.common
  )

lazy val networkCommon = (project in file("common/network-common"))
  .dependsOn(commonUtilsJava, tags % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-network-common",
    libraryDependencies ++= Seq(
      Database.leveldbjni,
      Database.rocksdb,
      Jackson.databind,
      Jackson.annotations,
      Metrics.core,
      Logging.slf4jApi % Provided,
      Google.jsr305,
      Google.guava,
      Google.failureaccess,
      Commons.crypto,
      Security.tink,
      Misc.roaringBitmap,
      // Test dependencies
      Security.bouncycastleBcprov % sbt.Test,
      Security.bouncycastleBcpkix % sbt.Test
    ) ++ Netty.allWithNatives ++ TestDeps.common
  )

lazy val networkShuffle = (project in file("common/network-shuffle"))
  .dependsOn(networkCommon, tags % "test->test", networkCommon % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-network-shuffle",
    libraryDependencies ++= Seq(
      Metrics.core,
      Logging.slf4jApi % Provided,
      Google.guava,
      Misc.roaringBitmap,
      Commons.io % sbt.Test
    ) ++ TestDeps.common
  )

lazy val launcher = (project in file("launcher"))
  .dependsOn(tags % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-launcher",
    libraryDependencies ++= Logging.all.map(_ % sbt.Test) ++ TestDeps.common
  )

// =============================================================================
// TIER 4: SQL API
// =============================================================================

// Shims module for Spark Connect compatibility
lazy val connectShims = (project in file("sql/connect/shims"))
  .dependsOn(tags % "test->test")
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-connect-shims",
    libraryDependencies ++= TestDeps.common
  )

lazy val sqlApi = (project in file("sql/api"))
  .dependsOn(commonUtils, unsafe, sketch, connectShims, tags % "test->test")
  .enablePlugins(Antlr4Plugin)
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-sql-api",
    // ANTLR4 configuration
    Antlr4 / antlr4Version := Versions.antlr4,
    Antlr4 / antlr4GenListener := true,
    Antlr4 / antlr4GenVisitor := true,
    Antlr4 / antlr4PackageName := Some("org.apache.spark.sql.catalyst.parser"),
    Compile / sourceGenerators += (Antlr4 / antlr4Generate).taskValue,
    libraryDependencies ++= Seq(
      Scala.reflect,
      Scala.parserCombinators,
      Commons.lang3,
      Misc.json4sJackson,
      CodeGen.antlr4Runtime,
      Arrow.vector,
      Arrow.memoryNetty
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 5: CORE
// =============================================================================

lazy val core = (project in file("core"))
  .dependsOn(
    launcher,
    kvstore,
    networkCommon,
    networkShuffle,
    unsafe,
    commonUtils,
    tags % "test->test",
    launcher % "test->test",
    networkCommon % "test->test",
    networkShuffle % "test->test",
    commonUtils % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(Shading.coreAssemblySettings)
  .settings(
    name := "spark-core",
    // Protobuf configuration
    Compile / PB.targets := Seq(
      PB.gens.java(Versions.protobuf) -> (Compile / sourceManaged).value / "protobuf"
    ),
    Compile / PB.protoSources := Seq(baseDirectory.value / "src" / "main" / "protobuf"),
    libraryDependencies ++= Seq(
      // Scala
      Scala.library,
      Scala.reflect,
      Scala.parallelCollections,
      Scala.xml,
      // Avro & Hadoop
      Avro.core,
      Avro.mapred,
      Hadoop.clientApi,
      Hadoop.clientRuntime,
      // Serialization
      Kryo.chill,
      Kryo.chillJava,
      Kryo.objenesis,
      // Jetty (for shading)
      Jetty.io % Provided,
      Jetty.http % Provided,
      Jetty.server % Provided,
      Jetty.security % Provided,
      Jetty.util % Provided,
      Jetty.client % Provided,
      Jetty.session % Provided,
      Jetty.ee10Servlet % Provided,
      Jetty.ee10Servlets % Provided,
      Jetty.ee10Plus % Provided,
      Jetty.ee10Proxy % Provided,
      Jetty.compressionServer % Provided,
      Jetty.compressionCommon % Provided,
      Jetty.compressionGzip % Provided,
      // Jersey
      Jersey.server,
      Jersey.client,
      Jersey.common,
      Jersey.containerServlet,
      Jersey.containerServletCore,
      Jersey.hk2,
      // Metrics
      Metrics.core,
      Metrics.jvm,
      Metrics.json,
      Metrics.graphite,
      Metrics.jmx,
      // Jackson
      Jackson.databind,
      Jackson.moduleScala,
      // JSON
      Misc.json4sJackson,
      // Python integration
      Misc.py4j,
      Misc.pickle,
      // Stream processing
      Misc.streamLib,
      // Networking
      Netty.all,
      // Protobuf
      Protobuf.java % Provided,
      // Commons
      Commons.codec,
      Commons.compress,
      Commons.lang3,
      Commons.text,
      Commons.math3,
      Commons.io,
      Commons.collections4,
      // Compression
      Compression.snappy,
      Compression.lz4,
      Compression.zstd,
      Compression.compressLzf,
      // ZooKeeper
      ZooKeeper.core,
      ZooKeeper.curatorRecipes,
      // Misc
      Google.guava % Provided,
      Google.failureaccess % Provided,
      Google.jsr305,
      Misc.jline,
      Misc.roaringBitmap,
      // Security
      Security.jjwtApi,
      Security.jjwtImpl % Runtime,
      Security.jjwtJackson % Runtime,
      // Database
      Database.derby % sbt.Test,
      Database.derbyTools % sbt.Test,
      // Test
      Hadoop.clientMinicluster % sbt.Test,
      Hadoop.minikdc % sbt.Test,
      TestDeps.selenium % sbt.Test,
      TestDeps.htmlunitDriver % sbt.Test,
      TestDeps.scalatestPlusSelenium % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test,
      TestDeps.curatorTest % sbt.Test,
      TestDeps.jnrPosix % sbt.Test
    ) ++ Logging.all ++ Netty.allWithNatives ++ TestDeps.common
  )

// =============================================================================
// TIER 6: SQL ENGINE
// =============================================================================

lazy val catalyst = (project in file("sql/catalyst"))
  .dependsOn(
    core,
    sqlApi,
    unsafe,
    sketch,
    variant,
    tags % "test->test",
    core % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-catalyst",
    // Larger heap for tests
    Test / javaOptions += "-Xmx4g",
    libraryDependencies ++= Seq(
      // Code generation
      CodeGen.janino,
      CodeGen.commonsCompiler,
      // Data processing
      Misc.univocity,
      Misc.xmlSchema,
      Misc.datasketches,
      // Jackson
      Jackson.databind,
      Jackson.dataformatYaml,
      // Test
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test,
      TestDeps.scalacheck % sbt.Test
    ) ++ TestDeps.common
  )

lazy val sql = (project in file("sql/core"))
  .dependsOn(
    core,
    catalyst,
    sqlApi,
    sketch,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-sql",
    // SPARK-54830: Larger heap for AdaptiveQueryExecSuite OOM
    Test / javaOptions += "-Xmx6g",
    // Protobuf configuration
    Compile / PB.targets := Seq(
      PB.gens.java(Versions.protobuf) -> (Compile / sourceManaged).value / "protobuf"
    ),
    Compile / PB.protoSources := Seq(baseDirectory.value / "src" / "main" / "protobuf"),
    libraryDependencies ++= Seq(
      // Database
      Database.rocksdb,
      // Data formats
      Misc.univocity,
      Orc.core,
      Orc.mapreduce,
      Hive.storageApi,
      Parquet.column,
      Parquet.hadoop,
      Parquet.common,
      Parquet.encoding,
      // Web
      Jetty.ee10Webapp,
      // Jackson
      Jackson.databind,
      // Misc
      Misc.xmlSchema,
      CodeGen.xbeanAsm,
      // Arrow
      Arrow.compression,
      // Test
      Database.derby % sbt.Test,
      Database.derbyTools % sbt.Test,
      Database.h2 % sbt.Test,
      Parquet.avro
    ) ++ TestDeps.common
  )

lazy val pipelines = (project in file("sql/pipelines"))
  .dependsOn(
    core,
    sql,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    sql % "test->test",
    sqlApi % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-pipelines",
    libraryDependencies ++= Seq(
      // Scala
      Scala.library,
      Scala.parallelCollections,
      // Test
      TestDeps.scalacheck % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 7: STREAMING & ML
// =============================================================================

lazy val streaming = (project in file("streaming"))
  .dependsOn(
    core,
    tags % "test->test",
    core % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-streaming",
    libraryDependencies ++= Seq(
      // Scala
      Scala.library,
      Scala.parallelCollections,
      // Jetty (shaded by core)
      Google.guava,
      Jetty.server,
      Jetty.ee10Plus,
      Jetty.util,
      Jetty.http,
      Jetty.ee10Servlet,
      Jetty.ee10Servlets,
      // Test
      TestDeps.scalacheck % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test,
      TestDeps.selenium % sbt.Test,
      TestDeps.htmlunitDriver % sbt.Test,
      TestDeps.scalatestPlusSelenium % sbt.Test
    ) ++ TestDeps.common
  )

lazy val mlLibLocal = (project in file("mllib-local"))
  .dependsOn(
    tags,
    tags % "test->test",
    core % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-mllib-local",
    libraryDependencies ++= Seq(
      // Math
      ML.breeze,
      Commons.math3,
      ML.netlibBlas,
      // Test
      TestDeps.scalacheck % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test
    ) ++ TestDeps.common
  )

lazy val graphx = (project in file("graphx"))
  .dependsOn(
    core,
    mlLibLocal,
    tags % "test->test",
    core % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-graphx",
    libraryDependencies ++= Seq(
      // Code generation
      CodeGen.xbeanAsm,
      // Math
      Google.guava,
      ML.netlibBlas,
      ML.arpackCombined,
      // Test
      TestDeps.scalacheck % sbt.Test
    ) ++ TestDeps.common
  )

lazy val mllib = (project in file("mllib"))
  .dependsOn(
    core,
    streaming,
    sql,
    catalyst,
    graphx,
    mlLibLocal,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    sql % "test->test",
    streaming % "test->test",
    mlLibLocal % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-mllib",
    libraryDependencies ++= Seq(
      // Scala
      Scala.parserCombinators,
      Scala.parallelCollections,
      // Math
      ML.breeze,
      Commons.math3,
      ML.netlibBlas,
      ML.netlibLapack,
      ML.netlibArpack,
      // PMML for model export
      ML.pmmlModel,
      ML.jaxbRuntime,
      ML.jakartaXmlBindApi,
      // Test
      TestDeps.scalacheck % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 8: HIVE
// =============================================================================

lazy val hive = (project in file("sql/hive"))
  .dependsOn(
    core,
    sql,
    pipelines,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    sql % "test->test",
    pipelines % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-hive",
    // Disable assertions for some Hive tests
    Test / javaOptions ++= Seq("-da", "-Xmx4g", "-Xss64m"),
    libraryDependencies ++= Seq(
      // Scala
      Scala.library,
      Scala.parallelCollections,
      Scala.compiler % sbt.Test,
      // Commons
      Commons.lang2,
      Commons.codec,
      Commons.httpClient,
      // Hive
      Hive.common,
      Hive.exec,
      Hive.metastore,
      Hive.serde,
      Hive.shims,
      Hive.llapCommon,
      Hive.llapClient,
      // Avro
      Avro.core,
      Avro.mapred,
      // Thrift
      Thrift.libthrift,
      Thrift.libfb303,
      // Datanucleus
      Datanucleus.core,
      // Hadoop
      Hadoop.clientRuntime,
      // Servlet
      Servlet.javaxServletApi,
      // Misc
      Misc.jodaTime,
      Google.jsr305,
      // Database
      Database.derby,
      Database.derbyTools,
      // Security
      Security.bouncycastleBcpkix,
      // Test
      Parquet.hadoop % sbt.Test classifier "tests",
      TestDeps.scalacheck % sbt.Test
    ) ++ TestDeps.common
  )

lazy val hiveThriftserver = (project in file("sql/hive-thriftserver"))
  .dependsOn(
    hive,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    hive % "test->test",
    sql % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-hive-thriftserver",
    libraryDependencies ++= Seq(
      // Scala
      Scala.parallelCollections,
      // Google
      Google.guava,
      // Hive
      Hive.cli,
      Hive.jdbc,
      Hive.beeline,
      Hive.serviceRpc,
      Hive.service,
      // Jetty (provided - shaded by core)
      Jetty.server % Provided,
      Jetty.ee10Servlet % Provided,
      Servlet.javaxServletApi % Provided,
      // Commons
      Commons.cli,
      Commons.httpCore,
      // Misc
      Misc.jpam,
      // Test
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test,
      TestDeps.selenium % sbt.Test,
      TestDeps.htmlunitDriver % sbt.Test
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 9: REPL
// =============================================================================

lazy val repl = (project in file("repl"))
  .dependsOn(
    core,
    sql,
    mllib % Runtime,
    tags % "test->test",
    core % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-repl",
    libraryDependencies ++= Seq(
      // Scala
      Scala.library,
      Scala.compiler,
      Scala.reflect,
      // Logging
      Logging.julToSlf4j,
      // Jetty (shaded by core)
      Jetty.server,
      Jetty.ee10Plus,
      Jetty.util,
      Jetty.http,
      // Code generation
      CodeGen.xbeanAsm,
      // Test
      TestDeps.scalacheck % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 10: CONNECTORS
// =============================================================================

lazy val avro = (project in file("connector/avro"))
  .dependsOn(
    sql % Provided,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    sql % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-avro",
    libraryDependencies ++= Seq(
      // Scala
      Scala.parallelCollections,
      // Compression (for Avro)
      Compression.xz,
      // Test
      TestDeps.scalacheck % sbt.Test
    ) ++ TestDeps.common
  )

lazy val protobuf = (project in file("connector/protobuf"))
  .dependsOn(
    sql % Provided,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    sql % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(Shading.protobufAssemblySettings)
  .settings(
    name := "spark-protobuf",
    libraryDependencies ++= Seq(
      // Scala
      Scala.parallelCollections,
      // Protobuf
      Protobuf.java,
      Protobuf.javaUtil,
      // Test
      TestDeps.scalacheck % sbt.Test
    ) ++ TestDeps.common
  )

// Hadoop Cloud Integration (S3, Azure, GCS)
lazy val hadoopCloud = (project in file("hadoop-cloud"))
  .dependsOn(
    sql % Provided,
    tags % "test->test",
    core % "test->test",
    sql % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-hadoop-cloud",
    libraryDependencies ++= Seq(
      // Hadoop
      Hadoop.clientApi % Provided,
      Hadoop.clientRuntime,
      Hadoop.aws,
      Hadoop.azure,
      Hadoop.cloudStorage,
      // Cloud SDKs
      Cloud.awsSdkBundle,
      Cloud.analyticsAcceleratorS3,
      Cloud.gcsConnector,
      Cloud.wildflyOpenssl,
      // Jackson
      Jackson.databind,
      Jackson.annotations,
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % Versions.jackson,
      // HTTP
      Commons.httpClient,
      Commons.httpCore,
      // Jetty
      Jetty.util,
      "org.eclipse.jetty" % "jetty-util-ajax" % Versions.jetty,
      // Misc
      Misc.jodaTime
    ) ++ TestDeps.common
  )

// Profiler - async-profiler based executor profiling
lazy val profiler = (project in file("connector/profiler"))
  .dependsOn(
    core % Provided
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-profiler",
    libraryDependencies ++= Seq(
      // Async profiler loader with multi-platform binaries
      Profiler.apLoaderAll % Provided
    )
  )

// Kafka token provider (base for Kafka connectors)
lazy val kafkaTokenProvider = (project in file("connector/kafka-0-10-token-provider"))
  .dependsOn(
    core % Provided,
    tags % "test->test",
    core % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-token-provider-kafka-0-10",
    libraryDependencies ++= Seq(
      // Kafka
      Kafka.clients,
      // Hadoop
      Hadoop.clientRuntime,
      // Test
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test
    ) ++ TestDeps.common
  )

// Kafka Streaming connector
lazy val kafkaStreaming = (project in file("connector/kafka-0-10"))
  .dependsOn(
    kafkaTokenProvider,
    streaming % Provided,
    tags % "test->test",
    core % "test->test",
    streaming % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-streaming-kafka-0-10",
    libraryDependencies ++= Seq(
      // Scala
      Scala.parallelCollections,
      // Kafka
      Kafka.clients,
      Kafka.core % sbt.Test,
      // ZooKeeper (test)
      ZooKeeper.core % sbt.Test,
      // Test
      TestDeps.scalacheck % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test,
      TestDeps.jmockJunit5 % sbt.Test
    ) ++ TestDeps.common
  )

// Kafka SQL (Structured Streaming) connector
lazy val kafkaSql = (project in file("connector/kafka-0-10-sql"))
  .dependsOn(
    kafkaTokenProvider,
    sql % Provided,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    sql % "test->test",
    kafkaTokenProvider % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-sql-kafka-0-10",
    libraryDependencies ++= Seq(
      // Scala
      Scala.parallelCollections,
      // Kafka
      Kafka.clients,
      Kafka.core % sbt.Test,
      // Utilities
      Google.jsr305,
      Commons.pool2,
      // Hadoop (test)
      Hadoop.minikdc % sbt.Test,
      // ZooKeeper (test)
      ZooKeeper.core % sbt.Test,
      // Jetty (test)
      Jetty.ee10Servlet % sbt.Test,
      // Test
      TestDeps.scalacheck % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test,
      TestDeps.jmockJunit5 % sbt.Test
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 11: RESOURCE MANAGERS
// =============================================================================

// YARN Shuffle Service (network-yarn)
lazy val networkYarn = (project in file("common/network-yarn"))
  .dependsOn(
    networkShuffle,
    tags % Provided,
    tags % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-network-yarn",
    libraryDependencies ++= Seq(
      // Hadoop
      Hadoop.clientApi,
      Hadoop.clientRuntime,
      // Provided
      Google.guava % Provided,
      Logging.slf4jApi % Provided
    ) ++ TestDeps.common
  )

// YARN Resource Manager
lazy val yarn = (project in file("resource-managers/yarn"))
  .dependsOn(
    core,
    tags % "test->test",
    core % "test->test",
    networkCommon % "test->test",
    networkYarn % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-yarn",
    libraryDependencies ++= Seq(
      // Hadoop
      Hadoop.clientApi,
      Hadoop.clientRuntime,
      Hadoop.clientMinicluster % sbt.Test,
      // Guava (shaded by core, but needed for compile)
      Google.guava,
      // Jetty (shaded by core)
      Jetty.server,
      Jetty.ee10Plus,
      Jetty.util,
      Jetty.http,
      Jetty.ee10Servlet,
      Jetty.ee10Servlets,
      // Test
      ML.jaxbApi % sbt.Test,
      Security.bouncycastleBcprov % sbt.Test,
      Security.bouncycastleBcpkix % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test
    ) ++ TestDeps.common
  )

// Kubernetes Resource Manager
lazy val kubernetes = (project in file("resource-managers/kubernetes/core"))
  .dependsOn(
    core,
    tags % "test->test",
    core % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-kubernetes",
    // Include volcano sources
    Compile / unmanagedSourceDirectories += baseDirectory.value / "volcano" / "src" / "main" / "scala",
    Test / unmanagedSourceDirectories += baseDirectory.value / "volcano" / "src" / "test" / "scala",
    libraryDependencies ++= Seq(
      // Kubernetes client
      Kubernetes.client,
      Kubernetes.volcanoModel,
      Kubernetes.volcanoClient,
      // Jackson YAML (required by k8s client but excluded)
      Jackson.dataformatYaml,
      // Guava (shaded by core)
      Google.guava,
      // Test
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test,
      TestDeps.jmockJunit5 % sbt.Test
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 12: SPARK CONNECT
// =============================================================================

// Connect Common - shared protobuf definitions and gRPC stubs
lazy val connectCommon = (project in file("sql/connect/common"))
  .dependsOn(
    sqlApi,
    tags % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-connect-common",
    // Protobuf configuration for Connect with gRPC plugin
    Compile / PB.targets := Seq(
      PB.gens.java(Versions.protobuf) -> (Compile / sourceManaged).value / "protobuf",
      PB.gens.plugin("grpc-java") -> (Compile / sourceManaged).value / "protobuf"
    ),
    Compile / PB.protoSources := Seq(baseDirectory.value / "src" / "main" / "protobuf"),
    // Include the proto source directory for inter-file imports
    Compile / PB.includePaths ++= Seq(baseDirectory.value / "src" / "main" / "protobuf"),
    libraryDependencies ++= Seq(
      // Scala
      Scala.library,
      // Protobuf (with proto files for standard types like google/protobuf/any.proto)
      Protobuf.java % "protobuf",
      // gRPC
      Grpc.netty,
      Grpc.protobuf,
      Grpc.services,
      Grpc.stub,
      Grpc.inprocess,
      // Netty for gRPC
      Netty.codecHttp2,
      Netty.handlerProxy,
      Netty.transportNativeUnixCommon,
      // Compression
      Compression.zstd,
      // gRPC protoc plugin (for code generation)
      "io.grpc" % "protoc-gen-grpc-java" % Versions.grpc asProtocPlugin()
    ) ++ TestDeps.common
  )

// Connect Server
// Note: Filter out connect-shims from classpath as we use real Spark classes
lazy val connect = (project in file("sql/connect/server"))
  .dependsOn(
    core,
    catalyst,
    sql,
    mllib,
    tags,
    connectCommon,
    pipelines,
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    sql % "test->test",
    pipelines % "test->test",
    connectCommon % "test->test",
    avro % sbt.Test,
    protobuf % sbt.Test,
    repl % "test->test"
  )
  .settings(
    // Filter out connect-shims classes from classpath since we use real Spark classes
    Compile / dependencyClasspath := {
      val cp = (Compile / dependencyClasspath).value
      cp.filterNot(_.data.getAbsolutePath.contains("connect/shims"))
    }
  )
  .settings(sparkModuleSettings)
  .settings(Shading.connectAssemblySettings)
  .settings(
    name := "spark-connect",
    libraryDependencies ++= Seq(
      // Scala
      Scala.parallelCollections,
      // Servlet
      Servlet.jakartaServletApi,
      Servlet.javaxServletApi,
      // Google
      Google.guava,
      Google.failureaccess,
      // Protobuf
      Protobuf.java,
      Protobuf.javaUtil,
      // gRPC
      Grpc.netty,
      Grpc.protobuf,
      Grpc.services,
      Grpc.stub,
      // Netty (provided - from core)
      Netty.codecHttp2 % Provided,
      Netty.handlerProxy % Provided,
      Netty.transportNativeUnixCommon % Provided,
      // Test
      Database.h2 % sbt.Test,
      TestDeps.scalacheck % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test
    ) ++ TestDeps.common
  )

// Connect Client JVM - Thin client for Spark Connect
lazy val connectClientJvm = (project in file("sql/connect/client/jvm"))
  .dependsOn(
    connectCommon,
    sqlApi,
    connectShims,
    sketch,
    tags % "test->test",
    sqlApi % "test->test",
    commonUtils % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(Shading.connectClientAssemblySettings)
  .settings(
    name := "spark-connect-client-jvm",
    libraryDependencies ++= Seq(
      // Scala
      Scala.compiler,
      Scala.xml,
      // Protobuf (compile scope - not provided, for shading)
      Protobuf.java,
      Protobuf.javaUtil,
      // Google
      Google.guava,
      Google.failureaccess,
      // Compression
      Compression.zstd,
      // REPL support (provided - optional for Connect shell)
      Ammonite.core % Provided,
      Semanticdb.shared % Provided,
      // Test
      Commons.io % sbt.Test,
      TestDeps.scalacheck % sbt.Test,
      Mima.core % sbt.Test
    ) ++ TestDeps.common
  )

// Connect Client JDBC - JDBC driver for Spark Connect
lazy val connectClientJdbc = (project in file("sql/connect/client/jdbc"))
  .dependsOn(
    connectClientJvm,
    connectClientJvm % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(Shading.connectClientAssemblySettings)
  .settings(
    name := "spark-connect-client-jdbc",
    libraryDependencies ++= TestDeps.common
  )

// =============================================================================
// TIER 13: TOOLS & EXAMPLES
// =============================================================================

// Development tools (code generation, bytecode analysis)
lazy val tools = (project in file("tools"))
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-tools",
    publish / skip := true,
    libraryDependencies ++= Seq(
      // Scala
      Scala.reflect,
      Scala.compiler,
      // Bytecode analysis
      CodeGen.classutil,
      CodeGen.asm,
      CodeGen.asmCommons,
      CodeGen.asmUtil
    )
  )

// Examples (all dependencies provided)
lazy val examples = (project in file("examples"))
  .dependsOn(
    core % Provided,
    streaming % Provided,
    mllib % Provided,
    hive % Provided,
    graphx % Provided,
    kafkaStreaming % Provided,
    kafkaSql % Provided
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-examples",
    publish / skip := true,
    libraryDependencies ++= Seq(
      // CLI parser
      Misc.scopt,
      // All provided via dependencies
      Commons.math3 % Provided,
      // Test
      TestDeps.scalacheck % sbt.Test
    ) ++ TestDeps.common
  )

// =============================================================================
// TIER 14: OPTIONAL CONNECTORS (Special licensing)
// =============================================================================

// Kinesis integration (ASL license)
lazy val kinesisAsl = (project in file("connector/kinesis-asl"))
  .dependsOn(
    streaming,
    tags,
    tags % "test->test",
    core % "test->test",
    streaming % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-streaming-kinesis-asl",
    // Exclude example files that are in src/main (should be in examples module)
    Compile / unmanagedSources / excludeFilter := HiddenFileFilter || "JavaKinesisWordCountASL.java",
    libraryDependencies ++= Seq(
      // AWS Kinesis
      AwsKinesis.client,
      AwsKinesis.auth,
      AwsKinesis.sts,
      AwsKinesis.apacheClient,
      AwsKinesis.regions,
      AwsKinesis.dynamodb,
      AwsKinesis.kinesis,
      AwsKinesis.cloudwatch,
      AwsKinesis.sdkCore,
      // Jackson
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % Versions.jackson,
      // Test
      AwsKinesis.producer % sbt.Test,
      TestDeps.mockitoCore % sbt.Test,
      TestDeps.byteBuddy % sbt.Test,
      TestDeps.byteBuddyAgent % sbt.Test,
      TestDeps.scalacheck % sbt.Test
    ) ++ TestDeps.common
  )

// Ganglia integration (LGPL license)
lazy val gangliaLgpl = (project in file("connector/spark-ganglia-lgpl"))
  .dependsOn(core)
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-ganglia-lgpl",
    libraryDependencies ++= Seq(
      Ganglia.gmetric4j
    )
  )

// =============================================================================
// TIER 15: ASSEMBLY (Packaging modules)
// =============================================================================

// Main Spark assembly - aggregates all modules for distribution
lazy val assembly = (project in file("assembly"))
  .dependsOn(
    core,
    mllib,
    streaming,
    graphx,
    sql,
    repl,
    connect,
    connectClientJvm % Provided,
    connectClientJdbc % Provided,
    avro % Provided,
    protobuf % Provided
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-assembly",
    publish / skip := true,
    // Assembly is a POM-only module - no sources to compile
    Compile / sources := Seq.empty,
    Test / sources := Seq.empty,
    libraryDependencies ++= Seq(
      Google.guava,
      Google.failureaccess
    )
  )

// Kafka streaming assembly
lazy val kafkaAssembly = (project in file("connector/kafka-0-10-assembly"))
  .dependsOn(
    kafkaStreaming,
    streaming % Provided
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-streaming-kafka-0-10-assembly",
    publish / skip := true,
    // Assembly module - no additional sources
    Compile / sources := Seq.empty,
    Test / sources := Seq.empty,
    libraryDependencies ++= Seq(
      // Mark as provided - already in Spark assembly
      Commons.codec % Provided,
      Commons.lang2 % Provided,
      Protobuf.java % Provided,
      Hadoop.clientApi % Provided,
      Hadoop.clientRuntime % Provided,
      Avro.mapred % Provided,
      ZooKeeper.curatorRecipes % Provided,
      ZooKeeper.core % Provided,
      Logging.log4jApi % Provided,
      Logging.log4jCore % Provided,
      Logging.log4j12Api % Provided,
      Scala.library % Provided,
      Logging.slf4jApi % Provided,
      Logging.log4jSlf4j2Impl % Provided,
      Compression.snappy % Provided
    )
  )

// Kinesis streaming assembly (ASL license)
lazy val kinesisAslAssembly = (project in file("connector/kinesis-asl-assembly"))
  .dependsOn(
    kinesisAsl,
    streaming % Provided
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-streaming-kinesis-asl-assembly",
    publish / skip := true,
    // Assembly module - no additional sources
    Compile / sources := Seq.empty,
    Test / sources := Seq.empty,
    libraryDependencies ++= Seq(
      // Mark as provided - already in Spark assembly
      Jackson.databind % Provided,
      Commons.lang2 % Provided,
      Jersey.client % Provided,
      Jersey.common % Provided,
      Jersey.server % Provided,
      Logging.log4jApi % Provided,
      Logging.log4jCore % Provided,
      Logging.log4j12Api % Provided,
      Hadoop.clientApi % Provided,
      Hadoop.clientRuntime % Provided,
      Avro.mapred % Provided,
      ZooKeeper.curatorRecipes % Provided,
      ZooKeeper.core % Provided,
      Logging.slf4jApi % Provided,
      Logging.log4jSlf4j2Impl % Provided,
      Compression.snappy % Provided
    )
  )

// =============================================================================
// TIER 16: INTEGRATION TESTS
// =============================================================================

// Docker-based integration tests
lazy val dockerIntegrationTests = (project in file("connector/docker-integration-tests"))
  .dependsOn(
    tags % "test->test",
    core % "test->test",
    catalyst % "test->test",
    sql % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-docker-integration-tests",
    publish / skip := true,
    // Test-only module
    Compile / sources := Seq.empty,
    libraryDependencies ++= Seq(
      Google.guava % sbt.Test
    ) ++ TestDeps.common
  )

// Kubernetes integration tests
lazy val kubernetesIntegrationTests = (project in file("resource-managers/kubernetes/integration-tests"))
  .dependsOn(
    core,
    kubernetes % sbt.Test,
    tags % "test->test",
    core % "test->test"
  )
  .settings(sparkModuleSettings)
  .settings(
    name := "spark-kubernetes-integration-tests",
    publish / skip := true,
    libraryDependencies ++= Seq(
      Kubernetes.client
    ) ++ TestDeps.common
  )
