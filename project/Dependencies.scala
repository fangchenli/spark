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

/**
 * Dependency definitions for native SBT build.
 * Dependencies are grouped by category for easier maintenance.
 */
object Dependencies {

  // ===== SCALA STANDARD LIBRARY =====
  object Scala {
    val library = "org.scala-lang" % "scala-library" % Versions.scala
    val reflect = "org.scala-lang" % "scala-reflect" % Versions.scala
    val compiler = "org.scala-lang" % "scala-compiler" % Versions.scala
    val parallelCollections = "org.scala-lang.modules" %% "scala-parallel-collections" % Versions.scalaParallelCollections
    val parserCombinators = "org.scala-lang.modules" %% "scala-parser-combinators" % Versions.scalaParserCombinators
    val xml = "org.scala-lang.modules" %% "scala-xml" % Versions.scalaXml
  }

  // ===== JACKSON (JSON) =====
  object Jackson {
    val core = "com.fasterxml.jackson.core" % "jackson-core" % Versions.jackson
    val databind = "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jackson
    val annotations = "com.fasterxml.jackson.core" % "jackson-annotations" % Versions.jackson
    val moduleScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % Versions.jackson
    val dataformatYaml = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % Versions.jackson
    val datatypeJsr310 = "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % Versions.jackson

    val bom = "com.fasterxml.jackson" % "jackson-bom" % Versions.jackson

    val all = Seq(core, databind, annotations)
  }

  // ===== PROTOBUF =====
  object Protobuf {
    val java = "com.google.protobuf" % "protobuf-java" % Versions.protobuf
    val javaUtil = "com.google.protobuf" % "protobuf-java-util" % Versions.protobuf
  }

  // ===== NETTY =====
  object Netty {
    val all = "io.netty" % "netty-all" % Versions.netty excludeAll(
      ExclusionRule(organization = "io.netty", name = "netty-tcnative-classes")
    )
    val buffer = "io.netty" % "netty-buffer" % Versions.netty
    val handler = "io.netty" % "netty-handler" % Versions.netty
    val transport = "io.netty" % "netty-transport" % Versions.netty
    val transportNativeEpoll = "io.netty" % "netty-transport-native-epoll" % Versions.netty
    val transportNativeKqueue = "io.netty" % "netty-transport-native-kqueue" % Versions.netty
    // Additional Netty modules for gRPC/Connect
    val codecHttp2 = "io.netty" % "netty-codec-http2" % Versions.netty
    val handlerProxy = "io.netty" % "netty-handler-proxy" % Versions.netty
    val transportNativeUnixCommon = "io.netty" % "netty-transport-native-unix-common" % Versions.netty

    // Native classifiers
    val epollLinuxX64 = "io.netty" % "netty-transport-native-epoll" % Versions.netty classifier "linux-x86_64"
    val epollLinuxArm64 = "io.netty" % "netty-transport-native-epoll" % Versions.netty classifier "linux-aarch_64"
    val kqueueOsxX64 = "io.netty" % "netty-transport-native-kqueue" % Versions.netty classifier "osx-x86_64"
    val kqueueOsxArm64 = "io.netty" % "netty-transport-native-kqueue" % Versions.netty classifier "osx-aarch_64"

    // TLS natives
    val tcnativeLinuxX64 = "io.netty" % "netty-tcnative-boringssl-static" % Versions.nettyTcnative classifier "linux-x86_64"
    val tcnativeLinuxArm64 = "io.netty" % "netty-tcnative-boringssl-static" % Versions.nettyTcnative classifier "linux-aarch_64"
    val tcnativeOsxX64 = "io.netty" % "netty-tcnative-boringssl-static" % Versions.nettyTcnative classifier "osx-x86_64"
    val tcnativeOsxArm64 = "io.netty" % "netty-tcnative-boringssl-static" % Versions.nettyTcnative classifier "osx-aarch_64"

    val allWithNatives = Seq(
      all, epollLinuxX64, epollLinuxArm64, kqueueOsxX64, kqueueOsxArm64,
      tcnativeLinuxX64, tcnativeLinuxArm64, tcnativeOsxX64, tcnativeOsxArm64
    )
  }

  // ===== JETTY =====
  object Jetty {
    val io = "org.eclipse.jetty" % "jetty-io" % Versions.jetty
    val http = "org.eclipse.jetty" % "jetty-http" % Versions.jetty
    val server = "org.eclipse.jetty" % "jetty-server" % Versions.jetty
    val security = "org.eclipse.jetty" % "jetty-security" % Versions.jetty
    val util = "org.eclipse.jetty" % "jetty-util" % Versions.jetty
    val client = "org.eclipse.jetty" % "jetty-client" % Versions.jetty
    val session = "org.eclipse.jetty" % "jetty-session" % Versions.jetty
    val ee10Servlet = "org.eclipse.jetty.ee10" % "jetty-ee10-servlet" % Versions.jetty
    val ee10Servlets = "org.eclipse.jetty.ee10" % "jetty-ee10-servlets" % Versions.jetty
    val ee10Plus = "org.eclipse.jetty.ee10" % "jetty-ee10-plus" % Versions.jetty
    val ee10Proxy = "org.eclipse.jetty.ee10" % "jetty-ee10-proxy" % Versions.jetty
    val ee10Webapp = "org.eclipse.jetty.ee10" % "jetty-ee10-webapp" % Versions.jetty
    // Compression modules (Jetty 12+)
    val compressionServer = "org.eclipse.jetty.compression" % "jetty-compression-server" % Versions.jetty
    val compressionCommon = "org.eclipse.jetty.compression" % "jetty-compression-common" % Versions.jetty
    val compressionGzip = "org.eclipse.jetty.compression" % "jetty-compression-gzip" % Versions.jetty

    val forShading = Seq(io, http, server, security, util, client, session,
      ee10Servlet, ee10Servlets, ee10Plus, ee10Proxy,
      compressionServer, compressionCommon, compressionGzip)
  }

  // ===== LOGGING =====
  object Logging {
    val slf4jApi = "org.slf4j" % "slf4j-api" % Versions.slf4j
    val julToSlf4j = "org.slf4j" % "jul-to-slf4j" % Versions.slf4j
    val jclOverSlf4j = "org.slf4j" % "jcl-over-slf4j" % Versions.slf4j
    val log4jApi = "org.apache.logging.log4j" % "log4j-api" % Versions.log4j
    val log4jCore = "org.apache.logging.log4j" % "log4j-core" % Versions.log4j
    val log4jSlf4j2Impl = "org.apache.logging.log4j" % "log4j-slf4j2-impl" % Versions.log4j
    val log4j12Api = "org.apache.logging.log4j" % "log4j-1.2-api" % Versions.log4j
    val log4jLayoutJson = "org.apache.logging.log4j" % "log4j-layout-template-json" % Versions.log4j

    val all = Seq(slf4jApi, julToSlf4j, jclOverSlf4j, log4jApi, log4jCore,
      log4jSlf4j2Impl, log4j12Api, log4jLayoutJson)
  }

  // ===== GOOGLE UTILITIES =====
  object Google {
    val guava = "com.google.guava" % "guava" % Versions.guava
    val failureaccess = "com.google.guava" % "failureaccess" % Versions.guavaFailureaccess
    val jsr305 = "com.google.code.findbugs" % "jsr305" % Versions.jsr305
    val gson = "com.google.code.gson" % "gson" % Versions.gson
  }

  // ===== APACHE COMMONS =====
  object Commons {
    val lang3 = "org.apache.commons" % "commons-lang3" % Versions.commonsLang3
    val lang2 = "commons-lang" % "commons-lang" % Versions.commonsLang2
    val text = "org.apache.commons" % "commons-text" % Versions.commonsText
    val codec = "commons-codec" % "commons-codec" % Versions.commonsCodec
    val compress = "org.apache.commons" % "commons-compress" % Versions.commonsCompress
    val io = "commons-io" % "commons-io" % Versions.commonsIo
    val math3 = "org.apache.commons" % "commons-math3" % Versions.commonsMath3
    val collections4 = "org.apache.commons" % "commons-collections4" % Versions.commonsCollections4
    val crypto = "org.apache.commons" % "commons-crypto" % Versions.commonsCrypto
    val cli = "commons-cli" % "commons-cli" % Versions.commonsCli
    val pool2 = "org.apache.commons" % "commons-pool2" % Versions.commonsPool2
    val httpClient = "org.apache.httpcomponents" % "httpclient" % Versions.commonsHttpClient
    val httpCore = "org.apache.httpcomponents" % "httpcore" % Versions.commonsHttpCore
  }

  // ===== SERIALIZATION =====
  object Kryo {
    val shaded = "com.esotericsoftware" % "kryo-shaded" % Versions.kryo
    val chill = "com.twitter" %% "chill" % Versions.chill excludeAll(
      ExclusionRule(organization = "org.apache.xbean", name = "xbean-asm7-shaded")
    )
    val chillJava = "com.twitter" % "chill-java" % Versions.chill excludeAll(
      ExclusionRule(organization = "org.apache.xbean", name = "xbean-asm7-shaded")
    )
    val objenesis = "org.objenesis" % "objenesis" % Versions.objenesis
  }

  // ===== HADOOP =====
  object Hadoop {
    val clientApi = "org.apache.hadoop" % "hadoop-client-api" % Versions.hadoop
    val clientRuntime = "org.apache.hadoop" % "hadoop-client-runtime" % Versions.hadoop
    val clientMinicluster = "org.apache.hadoop" % "hadoop-client-minicluster" % Versions.hadoop
    val minikdc = "org.apache.hadoop" % "hadoop-minikdc" % Versions.hadoop
    // Cloud storage connectors
    val aws = ("org.apache.hadoop" % "hadoop-aws" % Versions.hadoop).excludeAll(
      ExclusionRule(organization = "org.wildfly.openssl", name = "wildfly-openssl")
    )
    val azure = "org.apache.hadoop" % "hadoop-azure" % Versions.hadoop
    val huaweicloud = "org.apache.hadoop" % "hadoop-huaweicloud" % Versions.hadoop
    val cloudStorage = ("org.apache.hadoop" % "hadoop-cloud-storage" % Versions.hadoop).excludeAll(
      ExclusionRule(organization = "org.jacoco", name = "org.jacoco.agent"),
      ExclusionRule(organization = "org.apache.hadoop", name = "hadoop-cos"),
      ExclusionRule(organization = "org.apache.hadoop", name = "hadoop-huaweicloud")
    )
  }

  // ===== CLOUD PROVIDERS =====
  object Cloud {
    // AWS
    val awsSdkBundle = ("software.amazon.awssdk" % "bundle" % Versions.awsJavaSdkV2).excludeAll(
      ExclusionRule(organization = "*")
    )
    val analyticsAcceleratorS3 = "software.amazon.s3.analyticsaccelerator" % "analyticsaccelerator-s3" % Versions.analyticsAcceleratorS3
    // GCP
    val gcsConnector = ("com.google.cloud.bigdataoss" % "gcs-connector" % Versions.gcsConnector classifier "shaded").excludeAll(
      ExclusionRule(organization = "*")
    )
    // Misc
    val wildflyOpenssl = "org.wildfly.openssl" % "wildfly-openssl" % "2.2.5.Final"
    val okhttp = "com.squareup.okhttp3" % "okhttp" % "3.12.12"
    val okio = "com.squareup.okio" % "okio" % "1.17.6"
  }

  // ===== AWS KINESIS =====
  object AwsKinesis {
    private val kinesisExclusions = Seq(
      ExclusionRule(organization = "com.kjetland", name = "mbknor-jackson-jsonschema_2.12"),
      ExclusionRule(organization = "org.lz4", name = "lz4-java")
    )
    val client = ("software.amazon.kinesis" % "amazon-kinesis-client" % Versions.awsKinesisClient).excludeAll(kinesisExclusions: _*)
    val producer = ("software.amazon.kinesis" % "amazon-kinesis-producer" % Versions.awsKinesisProducer).excludeAll(kinesisExclusions: _*)
    // Individual AWS SDK v2 modules for Kinesis
    val auth = "software.amazon.awssdk" % "auth" % Versions.awsJavaSdkV2
    val sts = "software.amazon.awssdk" % "sts" % Versions.awsJavaSdkV2
    val apacheClient = "software.amazon.awssdk" % "apache-client" % Versions.awsJavaSdkV2
    val regions = "software.amazon.awssdk" % "regions" % Versions.awsJavaSdkV2
    val dynamodb = "software.amazon.awssdk" % "dynamodb" % Versions.awsJavaSdkV2
    val kinesis = "software.amazon.awssdk" % "kinesis" % Versions.awsJavaSdkV2
    val cloudwatch = "software.amazon.awssdk" % "cloudwatch" % Versions.awsJavaSdkV2
    val sdkCore = "software.amazon.awssdk" % "sdk-core" % Versions.awsJavaSdkV2
  }

  // ===== GANGLIA =====
  object Ganglia {
    val gmetric4j = "info.ganglia.gmetric4j" % "gmetric4j" % "1.0.10"
  }

  // ===== PROFILER =====
  object Profiler {
    val apLoaderAll = "me.bechberger" % "ap-loader-all" % Versions.apLoader
  }

  // ===== AVRO =====
  object Avro {
    val core = "org.apache.avro" % "avro" % Versions.avro excludeAll(
      ExclusionRule(organization = "com.thoughtworks.paranamer"),
      ExclusionRule(organization = "org.xerial.snappy", name = "snappy-java"),
      ExclusionRule(organization = "org.apache.commons", name = "commons-compress"),
      ExclusionRule(organization = "org.tukaani", name = "xz")
    )
    val mapred = "org.apache.avro" % "avro-mapred" % Versions.avro excludeAll(
      ExclusionRule(organization = "com.thoughtworks.paranamer"),
      ExclusionRule(organization = "org.xerial.snappy", name = "snappy-java"),
      ExclusionRule(organization = "org.apache.commons", name = "commons-compress"),
      ExclusionRule(organization = "org.tukaani", name = "xz")
    )
  }

  // ===== PARQUET =====
  object Parquet {
    val column = "org.apache.parquet" % "parquet-column" % Versions.parquet
    val hadoop = "org.apache.parquet" % "parquet-hadoop" % Versions.parquet
    val avro = "org.apache.parquet" % "parquet-avro" % Versions.parquet
    val common = "org.apache.parquet" % "parquet-common" % Versions.parquet
    val encoding = "org.apache.parquet" % "parquet-encoding" % Versions.parquet
  }

  // ===== ORC =====
  object Orc {
    val format = "org.apache.orc" % "orc-format" % Versions.orc classifier Versions.orcClassifier
    val core = "org.apache.orc" % "orc-core" % Versions.orc classifier Versions.orcClassifier
    val mapreduce = "org.apache.orc" % "orc-mapreduce" % Versions.orc classifier Versions.orcClassifier
  }

  // ===== ARROW =====
  object Arrow {
    val vector = "org.apache.arrow" % "arrow-vector" % Versions.arrow
    val memoryNetty = "org.apache.arrow" % "arrow-memory-netty" % Versions.arrow
    val compression = "org.apache.arrow" % "arrow-compression" % Versions.arrow
  }

  // ===== HIVE =====
  object Hive {
    // Common exclusions for Hive modules to avoid dependency conflicts
    // Be careful not to exclude the Hive internal packages
    private val hiveExclusions = Seq(
      ExclusionRule(organization = "org.apache.logging.log4j"),
      ExclusionRule(organization = "org.slf4j"),
      ExclusionRule(organization = "org.apache.curator"),
      ExclusionRule(organization = "org.apache.zookeeper"),
      ExclusionRule(organization = "org.eclipse.jetty"),
      ExclusionRule(organization = "org.eclipse.jetty.aggregate"),
      ExclusionRule(organization = "org.eclipse.jetty.orbit"),
      ExclusionRule(organization = "javax.servlet"),
      ExclusionRule(organization = "javax.servlet.jsp"),
      ExclusionRule(organization = "org.mortbay.jetty"),
      ExclusionRule(organization = "com.google.guava"),
      ExclusionRule(organization = "commons-logging"),
      ExclusionRule(organization = "org.apache.calcite"),
      ExclusionRule(organization = "org.apache.calcite.avatica"),
      ExclusionRule(organization = "com.google.protobuf"),
      ExclusionRule(organization = "org.codehaus.groovy"),
      ExclusionRule(organization = "io.netty"),
      ExclusionRule(organization = "stax"),
      ExclusionRule(organization = "com.sun.jersey"),
      ExclusionRule(organization = "org.glassfish.jersey.core"),
      // Keep org.apache.hadoop - needed for Hive's Hadoop integration
      ExclusionRule(organization = "org.datanucleus"),
      ExclusionRule(organization = "com.google.code.findbugs"),
      ExclusionRule(organization = "org.apache.ant"),
      ExclusionRule(organization = "ant")
      // Don't exclude: avro, thrift, derby, jackson - needed for Hive
    )
    // Standard Hive module (without classifier)
    private def hiveModule(name: String) =
      ("org.apache.hive" % s"hive-$name" % Versions.hive).excludeAll(hiveExclusions: _*)
    // hive-exec uses "core" classifier (shaded jar available on Maven Central)
    val exec = ("org.apache.hive" % "hive-exec" % Versions.hive classifier "core").excludeAll(hiveExclusions: _*)
    // Thriftserver-related modules need extra exclusions for llap and hive-exec transitive deps
    private val thriftExclusions = hiveExclusions ++ Seq(
      ExclusionRule(organization = "org.apache.hive", name = "hive-llap-common"),
      ExclusionRule(organization = "org.apache.hive", name = "hive-llap-client"),
      ExclusionRule(organization = "org.apache.hive", name = "hive-exec"),
      ExclusionRule(organization = "org.apache.hive", name = "hive-metastore"),
      ExclusionRule(organization = "org.apache.hive", name = "hive-common"),
      ExclusionRule(organization = "org.apache.hive", name = "hive-serde"),
      ExclusionRule(organization = "org.apache.hive", name = "hive-shims")
    )
    val beeline = ("org.apache.hive" % "hive-beeline" % Versions.hive).excludeAll(thriftExclusions: _*)
    val cli = ("org.apache.hive" % "hive-cli" % Versions.hive).excludeAll(thriftExclusions: _*)
    val jdbc = ("org.apache.hive" % "hive-jdbc" % Versions.hive).excludeAll(thriftExclusions: _*)
    // hive-service-rpc uses version 4.0.0 (newer than other Hive components) for Thrift classes
    // Exclude all transitive deps as they're provided by spark-hive
    val serviceRpc = ("org.apache.hive" % "hive-service-rpc" % Versions.hiveServiceRpc).excludeAll(
      ExclusionRule(organization = "*")
    )
    // hive-service contains BreakableService and other service classes
    val service = ("org.apache.hive" % "hive-service" % Versions.hive).excludeAll(thriftExclusions: _*)
    // Standard modules for spark-hive
    val common = hiveModule("common")
    val metastore = hiveModule("metastore")
    val serde = hiveModule("serde")
    val shims = hiveModule("shims")
    val llapCommon = hiveModule("llap-common")
    val llapClient = hiveModule("llap-client")
    val storageApi = "org.apache.hive" % "hive-storage-api" % Versions.hiveStorage
  }

  // ===== THRIFT =====
  object Thrift {
    val libthrift = "org.apache.thrift" % "libthrift" % Versions.libthrift
    val libfb303 = "org.apache.thrift" % "libfb303" % Versions.libfb303
  }

  // ===== DATANUCLEUS =====
  object Datanucleus {
    val core = "org.datanucleus" % "datanucleus-core" % Versions.datanucleusCore
  }

  // ===== SERVLET =====
  object Servlet {
    val javaxServletApi = "javax.servlet" % "javax.servlet-api" % Versions.javaxServlet
    val jakartaServletApi = "jakarta.servlet" % "jakarta.servlet-api" % Versions.jakartaServlet
  }

  // ===== ZOOKEEPER & CURATOR =====
  object ZooKeeper {
    val core = "org.apache.zookeeper" % "zookeeper" % Versions.zookeeper excludeAll(
      ExclusionRule(organization = "org.slf4j"),
      ExclusionRule(organization = "io.netty"),
      ExclusionRule(organization = "com.github.spotbugs")
    )
    val curatorRecipes = "org.apache.curator" % "curator-recipes" % Versions.curator excludeAll(
      ExclusionRule(organization = "org.apache.zookeeper")
    )
    val curatorClient = "org.apache.curator" % "curator-client" % Versions.curator
    val curatorFramework = "org.apache.curator" % "curator-framework" % Versions.curator
    val curatorTestDeps = "org.apache.curator" % "curator-test" % Versions.curator
  }

  // ===== METRICS =====
  object Metrics {
    val core = "io.dropwizard.metrics" % "metrics-core" % Versions.codahaleMetrics
    val jvm = "io.dropwizard.metrics" % "metrics-jvm" % Versions.codahaleMetrics
    val json = "io.dropwizard.metrics" % "metrics-json" % Versions.codahaleMetrics
    val graphite = "io.dropwizard.metrics" % "metrics-graphite" % Versions.codahaleMetrics excludeAll(
      ExclusionRule(organization = "com.rabbitmq")
    )
    val jmx = "io.dropwizard.metrics" % "metrics-jmx" % Versions.codahaleMetrics
  }

  // ===== DATABASE =====
  object Database {
    val derby = "org.apache.derby" % "derby" % Versions.derby
    val derbyTools = "org.apache.derby" % "derbytools" % Versions.derby
    val rocksdb = "org.rocksdb" % "rocksdbjni" % Versions.rocksdb
    val leveldbjni = "org.fusesource.leveldbjni" % "leveldbjni-all" % Versions.leveldbjni
    val h2 = "com.h2database" % "h2" % Versions.h2
  }

  // ===== CODE GENERATION =====
  object CodeGen {
    val janino = "org.codehaus.janino" % "janino" % Versions.janino
    val commonsCompiler = "org.codehaus.janino" % "commons-compiler" % Versions.janino
    val antlr4Runtime = "org.antlr" % "antlr4-runtime" % Versions.antlr4
    val xbeanAsm = "org.apache.xbean" % "xbean-asm9-shaded" % Versions.xbeanAsm
    // ASM bytecode library
    val asm = "org.ow2.asm" % "asm" % Versions.asm
    val asmCommons = "org.ow2.asm" % "asm-commons" % Versions.asm
    val asmUtil = "org.ow2.asm" % "asm-util" % Versions.asm
    // Classutil for tools
    val classutil = ("org.clapper" %% "classutil" % Versions.classutil).excludeAll(
      ExclusionRule(organization = "org.ow2.asm")
    )
  }

  // ===== COMPRESSION =====
  object Compression {
    val snappy = "org.xerial.snappy" % "snappy-java" % Versions.snappy
    val lz4 = "org.lz4" % "lz4-java" % Versions.lz4
    val zstd = "com.github.luben" % "zstd-jni" % Versions.zstd
    val compressLzf = "com.ning" % "compress-lzf" % Versions.compressLzf
    val xz = "org.tukaani" % "xz" % Versions.xz
  }

  // ===== SECURITY =====
  object Security {
    val bouncycastleBcprov = "org.bouncycastle" % "bcprov-jdk18on" % Versions.bouncycastle
    val bouncycastleBcpkix = "org.bouncycastle" % "bcpkix-jdk18on" % Versions.bouncycastle
    val tink = "com.google.crypto.tink" % "tink" % Versions.tink
    val jjwtApi = "io.jsonwebtoken" % "jjwt-api" % Versions.jjwt
    val jjwtImpl = "io.jsonwebtoken" % "jjwt-impl" % Versions.jjwt
    val jjwtJackson = "io.jsonwebtoken" % "jjwt-jackson" % Versions.jjwt
  }

  // ===== ML/MATH =====
  object ML {
    val breeze = "org.scalanlp" %% "breeze" % Versions.breeze excludeAll(
      ExclusionRule(organization = "junit"),
      ExclusionRule(organization = "org.apache.commons", name = "commons-math3")
    )
    // Netlib - native linear algebra
    val netlibBlas = "dev.ludovic.netlib" % "blas" % Versions.netlibLudovicDev
    val netlibLapack = "dev.ludovic.netlib" % "lapack" % Versions.netlibLudovicDev
    val netlibArpack = "dev.ludovic.netlib" % "arpack" % Versions.netlibLudovicDev
    // ARPACK for GraphX
    val arpackCombined = "net.sourceforge.f2j" % "arpack_combined_all" % Versions.arpackCombined
    // PMML for ML model export
    val pmmlModel = "org.jpmml" % "pmml-model" % Versions.pmmlModel
    // JAXB for PMML
    val jaxbRuntime = "org.glassfish.jaxb" % "jaxb-runtime" % Versions.jaxbRuntime
    val jakartaXmlBindApi = "jakarta.xml.bind" % "jakarta.xml.bind-api" % Versions.jakartaXmlBindApi
    val jaxbApi = "javax.xml.bind" % "jaxb-api" % Versions.jaxb
  }

  // ===== MISC =====
  object Misc {
    val icu4j = "com.ibm.icu" % "icu4j" % Versions.icu4j
    val jline = "jline" % "jline" % Versions.jline
    val jodaTime = "joda-time" % "joda-time" % Versions.joda
    val ivy = "org.apache.ivy" % "ivy" % Versions.ivy
    val oro = "oro" % "oro" % Versions.oro
    val xmlSchema = "org.apache.ws.xmlschema" % "xmlschema-core" % Versions.xmlSchema
    val roaringBitmap = "com.github.RoaringBitmap.RoaringBitmap" % "roaringbitmap" % Versions.roaringBitmap
    val univocity = "com.univocity" % "univocity-parsers" % Versions.univocity
    val datasketches = "org.apache.datasketches" % "datasketches-java" % Versions.datasketches
    val json4sJackson = "org.json4s" %% "json4s-jackson" % Versions.json4s
    // Python integration
    val py4j = "net.sf.py4j" % "py4j" % Versions.py4j
    val pickle = "net.razorvine" % "pickle" % Versions.pickle
    // Stream processing
    val streamLib = "com.clearspring.analytics" % "stream" % Versions.stream
    // PAM authentication
    val jpam = "net.sf.jpam" % "jpam" % Versions.jpam excludeAll(
      ExclusionRule(organization = "commons-logging")
    )
    // CLI parser for examples
    val scopt = "com.github.scopt" %% "scopt" % Versions.scopt
  }

  // ===== TESTING =====
  object TestDeps {
    val scalatest = "org.scalatest" %% "scalatest" % Versions.scalatest
    val scalatestFunSuite = "org.scalatest" %% "scalatest-funsuite" % Versions.scalatest
    val scalatestPlusScalacheck = "org.scalatestplus" %% "scalacheck-1-18" % Versions.scalatestPlusScalacheck
    val scalatestPlusMockito = "org.scalatestplus" %% "mockito-5-12" % Versions.scalatestPlusMockito
    val scalatestPlusSelenium = "org.scalatestplus" %% "selenium-4-21" % Versions.scalatestPlusSelenium
    val scalacheck = "org.scalacheck" %% "scalacheck" % Versions.scalacheck
    val mockitoCore = "org.mockito" % "mockito-core" % Versions.mockito
    val mockitoJunit = "org.mockito" % "mockito-junit-jupiter" % Versions.mockito
    val byteBuddy = "net.bytebuddy" % "byte-buddy" % Versions.byteBuddy
    val byteBuddyAgent = "net.bytebuddy" % "byte-buddy-agent" % Versions.byteBuddy
    val junit = "org.junit.jupiter" % "junit-jupiter" % Versions.junit
    val junitApi = "org.junit.jupiter" % "junit-jupiter-api" % Versions.junit
    val junitEngine = "org.junit.jupiter" % "junit-jupiter-engine" % Versions.junit
    val junitParams = "org.junit.jupiter" % "junit-jupiter-params" % Versions.junit
    val jupiterInterface = "com.github.sbt.junit" % "jupiter-interface" % Versions.sbtJupiterInterface
    val selenium = "org.seleniumhq.selenium" % "selenium-java" % Versions.selenium
    val htmlunitDriver = "org.seleniumhq.selenium" % "htmlunit3-driver" % Versions.htmlunitDriver
    val curatorTest = "org.apache.curator" % "curator-test" % Versions.curator
    val jnrPosix = "com.github.jnr" % "jnr-posix" % Versions.jnrPosix
    val jmockJunit5 = "org.jmock" % "jmock-junit5" % Versions.jmock

    val common = Seq(
      scalatest % sbt.Test,
      scalatestPlusScalacheck % sbt.Test,
      scalatestPlusMockito % sbt.Test,
      junit % sbt.Test,
      jupiterInterface % sbt.Test
    )
  }

  // ===== GRPC =====
  object Grpc {
    val netty = "io.grpc" % "grpc-netty" % Versions.grpc
    val protobuf = "io.grpc" % "grpc-protobuf" % Versions.grpc
    val stub = "io.grpc" % "grpc-stub" % Versions.grpc
    val services = "io.grpc" % "grpc-services" % Versions.grpc
    val inprocess = "io.grpc" % "grpc-inprocess" % Versions.grpc
  }

  // ===== KAFKA =====
  object Kafka {
    // Exclusions to avoid conflicts with Spark's versions
    private val kafkaExclusions = Seq(
      ExclusionRule(organization = "com.github.luben", name = "zstd-jni"),
      ExclusionRule(organization = "org.lz4", name = "lz4-java")
    )
    private val kafkaTestExclusions = kafkaExclusions ++ Seq(
      ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-core"),
      ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-databind"),
      ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-annotations"),
      ExclusionRule(organization = "commons-logging", name = "commons-logging")
    )
    val clients = ("org.apache.kafka" % "kafka-clients" % Versions.kafka).excludeAll(kafkaExclusions: _*)
    val core = ("org.apache.kafka" %% "kafka" % Versions.kafka).excludeAll(kafkaTestExclusions: _*)
  }

  // ===== JERSEY =====
  object Jersey {
    val server = "org.glassfish.jersey.core" % "jersey-server" % Versions.jersey
    val client = "org.glassfish.jersey.core" % "jersey-client" % Versions.jersey
    val common = "org.glassfish.jersey.core" % "jersey-common" % Versions.jersey
    val containerServlet = "org.glassfish.jersey.containers" % "jersey-container-servlet" % Versions.jersey
    val containerServletCore = "org.glassfish.jersey.containers" % "jersey-container-servlet-core" % Versions.jersey
    val hk2 = "org.glassfish.jersey.inject" % "jersey-hk2" % Versions.jersey
  }

  // ===== KUBERNETES =====
  object Kubernetes {
    private val k8sExclusions = Seq(
      ExclusionRule(organization = "com.fasterxml.jackson.core"),
      ExclusionRule(organization = "com.fasterxml.jackson.dataformat", name = "jackson-dataformat-yaml"),
      ExclusionRule(organization = "javax.annotation", name = "javax.annotation-api")
    )
    val client = ("io.fabric8" % "kubernetes-client" % Versions.kubernetesClient).excludeAll(k8sExclusions: _*)
    val volcanoModel = "io.fabric8" % "volcano-model" % Versions.kubernetesClient
    val volcanoClient = "io.fabric8" % "volcano-client" % Versions.kubernetesClient
  }

  // ===== CONNECT CLIENT =====
  object Ammonite {
    // Exclude jline jars in favor of the bundled jline from scala-compiler
    val core = ("com.lihaoyi" % s"ammonite_${Versions.scala}" % Versions.ammonite).excludeAll(
      ExclusionRule(organization = "org.jline")
    )
  }

  object Semanticdb {
    val shared = ("org.scalameta" %% "semanticdb-shared" % Versions.semanticdb).excludeAll(
      ExclusionRule(organization = "org.scala-lang", name = "scalap")
    )
  }

  object Mima {
    val core = "com.typesafe" %% "mima-core" % Versions.mima
  }

  // ===== COMMON EXCLUSION RULES =====
  object Exclusions {
    val groovy = ExclusionRule(organization = "org.codehaus.groovy")
    val logback = ExclusionRule(organization = "ch.qos.logback")
    val lz4Java = ExclusionRule(organization = "org.lz4", name = "lz4-java")
    val slf4jSimple = ExclusionRule(organization = "org.slf4j", name = "slf4j-simple")
    val javaxServlet = ExclusionRule(organization = "javax.servlet", name = "javax.servlet-api")
  }
}
