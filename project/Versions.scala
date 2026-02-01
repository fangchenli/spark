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

/**
 * Version constants for native SBT build.
 * Reads from versions.properties - the single source of truth for dependency versions.
 * This file is shared between Maven and SBT builds.
 */
object Versions {
  // Load properties from versions.properties file
  // Note: We use _root_.java to avoid shadowing by the 'java' val below
  private val props: _root_.java.util.Properties = {
    val p = new _root_.java.util.Properties()
    val propsFile = new _root_.java.io.File("versions.properties")
    if (propsFile.exists()) {
      val in = new _root_.java.io.FileInputStream(propsFile)
      try {
        p.load(in)
      } finally {
        in.close()
      }
    } else {
      throw new RuntimeException(
        s"versions.properties not found at ${propsFile.getAbsolutePath}. " +
        "This file is required for the SBT build.")
    }
    p
  }

  private def get(key: String): String = {
    val value = props.getProperty(key)
    if (value == null) {
      throw new RuntimeException(s"Property '$key' not found in versions.properties")
    }
    value
  }

  // ===== PROJECT =====
  val spark: String = get("spark.version")

  // ===== SCALA =====
  val scala: String = get("scala.version")
  val scalaBinary: String = get("scala.binary.version")
  val scalaParallelCollections: String = get("scala.parallel.collections.version")
  val scalaParserCombinators: String = get("scala.parser.combinators.version")
  val scalaXml: String = get("scala.xml.version")

  // ===== JAVA =====
  val java: String = get("java.version")
  val javaMinimum: String = get("java.minimum.version")

  // ===== HADOOP ECOSYSTEM =====
  val hadoop: String = get("hadoop.version")
  val zookeeper: String = get("zookeeper.version")
  val curator: String = get("curator.version")

  // ===== HIVE =====
  val hive: String = get("hive.version")
  val hiveStorage: String = get("hive.storage.version")

  // ===== DATA FORMATS =====
  val avro: String = get("avro.version")
  val parquet: String = get("parquet.version")
  val orc: String = get("orc.version")
  val orcClassifier: String = get("orc.classifier")
  val arrow: String = get("arrow.version")

  // ===== SERIALIZATION =====
  val jackson: String = get("jackson.version")
  val protobuf: String = get("protobuf.version")
  val kryo: String = get("kryo.version")
  val chill: String = get("chill.version")
  val gson: String = get("gson.version")
  val libthrift: String = get("libthrift.version")

  // ===== NETWORKING =====
  val netty: String = get("netty.version")
  val nettyTcnative: String = get("netty.tcnative.version")
  val jetty: String = get("jetty.version")
  val grpc: String = get("grpc.version")

  // ===== WEB/SERVLET =====
  val jakartaServlet: String = get("jakarta.servlet.version")
  val javaxServlet: String = get("javax.servlet.version")
  val jersey: String = get("jersey.version")

  // ===== LOGGING =====
  val slf4j: String = get("slf4j.version")
  val log4j: String = get("log4j.version")

  // ===== METRICS =====
  val codahaleMetrics: String = get("codahale.metrics.version")

  // ===== UTILITIES =====
  val guava: String = get("guava.version")
  val guavaFailureaccess: String = get("guava.failureaccess.version")
  val commonsCodec: String = get("commons.codec.version")
  val commonsCompress: String = get("commons.compress.version")
  val commonsIo: String = get("commons.io.version")
  val commonsLang2: String = get("commons.lang2.version")
  val commonsLang3: String = get("commons.lang3.version")
  val commonsPool2: String = get("commons.pool2.version")
  val commonsMath3: String = get("commons.math3.version")
  val commonsCollections4: String = get("commons.collections4.version")
  val commonsCrypto: String = get("commons.crypto.version")
  val commonsCli: String = get("commons.cli.version")
  val commonsHttpClient: String = get("commons.httpclient.version")
  val commonsHttpCore: String = get("commons.httpcore.version")
  val ivy: String = get("ivy.version")
  val oro: String = get("oro.version")

  // ===== CODE GENERATION =====
  val janino: String = get("janino.version")
  val antlr4: String = get("antlr4.version")
  val asm: String = get("asm.version")
  val xbeanAsm: String = get("xbean.asm.version")

  // ===== SECURITY =====
  val bouncycastle: String = get("bouncycastle.version")
  val tink: String = get("tink.version")
  val jjwt: String = get("jjwt.version")

  // ===== DATABASE =====
  val derby: String = get("derby.version")
  val rocksdb: String = get("rocksdb.version")
  val leveldbjni: String = get("leveldbjni.version")
  val datanucleusCore: String = get("datanucleus.core.version")

  // ===== MESSAGING =====
  val kafka: String = get("kafka.version")

  // ===== ML/MATH =====
  val netlibLudovicDev: String = get("netlib.ludovic.dev.version")
  val datasketches: String = get("datasketches.version")
  val breeze: String = get("breeze.version")

  // ===== AWS =====
  val awsJavaSdkV2: String = get("aws.java.sdk.v2.version")
  val awsKinesisClient: String = get("aws.kinesis.client.version")
  val awsKinesisProducer: String = get("aws.kinesis.producer.version")

  // ===== GCP =====
  val gcsConnector: String = get("gcs.connector.version")
  val analyticsAcceleratorS3: String = get("analytics.accelerator.s3.version")

  // ===== KUBERNETES =====
  val kubernetesClient: String = get("kubernetes.client.version")

  // ===== TESTING =====
  val junit: String = get("junit.version")
  val sbtJupiterInterface: String = get("sbt.jupiter.interface.version")
  val scalatest: String = get("scalatest.version")
  val scalatestPlusScalacheck: String = get("scalatest.plus.scalacheck.version")
  val scalatestPlusMockito: String = get("scalatest.plus.mockito.version")
  val scalatestPlusSelenium: String = get("scalatest.plus.selenium.version")
  val scalacheck: String = get("scalacheck.version")
  val mockito: String = get("mockito.version")
  val byteBuddy: String = get("byte.buddy.version")
  val selenium: String = get("selenium.version")
  val htmlunitDriver: String = get("htmlunit.driver.version")

  // ===== DATABASE DRIVERS (TEST) =====
  val mariadbClient: String = get("mariadb.java.client.version")
  val mysqlConnector: String = get("mysql.connector.version")
  val postgresql: String = get("postgresql.version")
  val db2Jcc: String = get("db2.jcc.version")
  val mssqlJdbc: String = get("mssql.jdbc.version")
  val ojdbc17: String = get("ojdbc17.version")
  val databricksJdbc: String = get("databricks.jdbc.version")
  val snowflakeJdbc: String = get("snowflake.jdbc.version")
  val terajdbc: String = get("terajdbc.version")
  val h2: String = get("h2.version")

  // ===== ADDITIONAL LIBRARIES =====
  val commonsText: String = get("commons.text.version")
  val objenesis: String = get("objenesis.version")
  val json4s: String = get("json4s.version")
  val stream: String = get("stream.version")
  val pmmlModel: String = get("pmml.model.version")
  val jaxbRuntime: String = get("jaxb.runtime.version")
  val jakartaXmlBindApi: String = get("jakarta.xml.bind.api.version")
  val jnrPosix: String = get("jnr.posix.version")
  val py4j: String = get("py4j.version")
  val pickle: String = get("pickle.version")
  val arpackCombined: String = get("arpack.combined.version")
  val libfb303: String = get("libfb303.version")
  val jpam: String = get("jpam.version")
  val hiveServiceRpc: String = get("hive.service.rpc.version")

  // ===== COMPRESSION =====
  val snappy: String = get("snappy.version")
  val lz4: String = get("lz4.version")
  val zstd: String = get("zstd.version")
  val compressLzf: String = get("compress.lzf.version")

  // ===== MISC =====
  val icu4j: String = get("icu4j.version")
  val jline: String = get("jline.version")
  val joda: String = get("joda.version")
  val jsr305: String = get("jsr305.version")
  val jaxb: String = get("jaxb.version")
  val xmlSchema: String = get("xml.schema.version")
  val roaringBitmap: String = get("roaring.bitmap.version")
  val univocity: String = get("univocity.version")
  val ammonite: String = get("ammonite.version")

  // ===== MIMA =====
  val mima: String = get("mima.version")

  // ===== PROFILER =====
  val apLoader: String = get("ap.loader.version")

  // ===== SBT PLUGINS =====
  // These are only used in SBT build
  val sbtCheckstyle: String = get("sbt.checkstyle.version")
  val checkstyle: String = get("checkstyle.version")
  val sbtAssembly: String = get("sbt.assembly.version")
  val sbtEclipse: String = get("sbt.eclipse.version")
  val scalastyleSbt: String = get("scalastyle.sbt.version")
  val sbtMima: String = get("sbt.mima.version")
  val sbtUnidoc: String = get("sbt.unidoc.version")
  val sbtRevolver: String = get("sbt.revolver.version")
  val sbtAntlr4: String = get("sbt.antlr4.version")
  val sbtProtoc: String = get("sbt.protoc.version")
  val sbtBom: String = get("sbt.bom.version")
  val sbtBloop: String = get("sbt.bloop.version")
}
