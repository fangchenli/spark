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
import scala.util.Properties

/**
 * Shared settings for native SBT build.
 */
object Settings {

  val sparkHome: File = file(".").getAbsoluteFile.getParentFile
  val testTempDir: String = s"$sparkHome/target/tmp"

  // ===== RESOLVER SETTINGS =====
  lazy val resolverSettings: Seq[Setting[_]] = Seq(
    resolvers ++= Seq(
      "gcs-maven-central-mirror" at "https://maven-central.storage-download.googleapis.com/maven2/",
      "jitpack" at "https://jitpack.io",
      Resolver.mavenLocal
    )
  )

  // ===== SCALA COMPILER SETTINGS =====
  // Supports both Scala 2.13 and Scala 3 with version-conditional flags
  // Use scalaBinaryVersion.value to allow command-line control via:
  //   sbt ++3.3.4 compile
  //   sbt -Dscala.version=3.3.4 compile
  lazy val scalaCompilerSettings: Seq[Setting[_]] = Seq(
    scalacOptions ++= {
      val isScala3 = scalaBinaryVersion.value.startsWith("3")

      // Scala 3 compiler options
      val scala3Opts = Seq(
        "-unchecked",
        "-deprecation",
        "-feature",
        "-Wunused:imports",
        "-Wconf:any:e",
        "-Wconf:cat=deprecation:wv",
        // Source compatibility mode (allows some Scala 2 syntax)
        "-source:3.3-migration"
      )

      // Scala 2.13 compiler options
      val scala2Opts = Seq(
        "-unchecked",
        "-deprecation",
        "-feature",
        "-explaintypes",
        // Treat warnings as errors, except deprecation
        "-Wconf:any:e",
        "-Wconf:cat=deprecation:wv",
        // Unused imports
        "-Wunused:imports",
        // Scaladoc warnings as verbose
        "-Wconf:cat=scaladoc:wv",
        // Various deprecation handling
        "-Wconf:cat=deprecation&origin=scala\\..*\\.CanBuildFrom:wv",
        "-Wconf:cat=deprecation&origin=scala\\.package\\|(deprecatedName|deprecatedOverriding):wv",
        "-Wconf:cat=deprecation&origin=scala\\.math\\.Numeric\\.signum:wv",
        "-Wconf:cat=deprecation&origin=org\\.apache\\.spark\\.SparkThrowable\\.getErrorClass:wv",
        "-Wconf:cat=deprecation&origin=org\\.apache\\.spark\\.sql\\.SparkGetErrorClassMessage\\.getSparkErrorMessage:wv",
        // Auto-application deprecation
        "-Wconf:msg=Auto-application:e",
        // Procedure syntax deprecation
        "-Wconf:cat=deprecation&msg=procedure syntax is deprecated:e",
        // Symbol literal deprecation
        "-Wconf:cat=deprecation&msg=symbol literal is deprecated:e"
      )

      val baseOpts = if (isScala3) scala3Opts else scala2Opts

      // Spark requires Java 17+, so -release flag is always available
      baseOpts ++ Seq("-release", Versions.java)
    },
    // Source path for scaladoc (Scala 2 only, Scala 3 uses different option)
    Compile / scalacOptions ++= {
      if (scalaBinaryVersion.value.startsWith("3")) Seq.empty
      else Seq(s"-sourcepath:${baseDirectory.value.getAbsolutePath}")
    },
    // Version-specific source directories (e.g., src/main/scala-2.13, src/main/scala-3)
    Compile / unmanagedSourceDirectories ++= {
      val sourceDir = (Compile / sourceDirectory).value
      val sbv = scalaBinaryVersion.value
      val versionSpecificDir = if (sbv.startsWith("3")) {
        sourceDir / "scala-3"
      } else {
        sourceDir / s"scala-$sbv"
      }
      if (versionSpecificDir.exists()) Seq(versionSpecificDir) else Seq.empty
    },
    Test / unmanagedSourceDirectories ++= {
      val sourceDir = (Test / sourceDirectory).value
      val sbv = scalaBinaryVersion.value
      val versionSpecificDir = if (sbv.startsWith("3")) {
        sourceDir / "scala-3"
      } else {
        sourceDir / s"scala-$sbv"
      }
      if (versionSpecificDir.exists()) Seq(versionSpecificDir) else Seq.empty
    }
  )

  // ===== JAVA COMPILER SETTINGS =====
  // Spark requires Java 17+, so --release flag is always available
  lazy val javaCompilerSettings: Seq[Setting[_]] = Seq(
    javacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-g",
      "-proc:full",
      s"--release=${Versions.java}"
    ),
    Compile / javacOptions ++= Seq("-Xlint:unchecked"),
    // Javadoc options - use := to override base javacOptions since they contain
    // compiler-only flags that javadoc doesn't understand (like -proc:full, --release)
    Compile / doc / javacOptions := Seq(
      "-Xdoclint:none",
      "--ignore-source-errors"
    )
  )

  // ===== TEST JVM OPTIONS =====
  val extraTestJavaArgs: Seq[String] = Seq(
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--add-modules=jdk.incubator.vector",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
    "-Djdk.reflect.useDirectMethodHandle=false",
    "-Dio.netty.tryReflectionSetAccessible=true",
    "-Dio.netty.allocator.type=pooled",
    "-Dio.netty.handler.ssl.defaultEndpointVerificationAlgorithm=NONE",
    "--enable-native-access=ALL-UNNAMED"
  )

  // Default excluded test tags
  val defaultExcludedTags: Seq[String] = {
    val baseTags = Seq(
      "org.apache.spark.tags.ChromeUITest",
      "org.apache.spark.deploy.k8s.integrationtest.YuniKornTag",
      "org.apache.spark.internal.io.cloud.IntegrationTestSuite"
    )
    // On Apple Silicon, exclude LevelDB tests since leveldbjni doesn't have ARM64 support
    val isAppleSilicon = sys.props("os.name").contains("Mac OS X") &&
      sys.props("os.arch") == "aarch64"
    if (isAppleSilicon) {
      baseTags :+ "org.apache.spark.tags.ExtendedLevelDBTest"
    } else {
      baseTags
    }
  }

  // ===== TEST SETTINGS =====
  lazy val testSettings: Seq[Setting[_]] = Seq(
    // Fork tests to separate JVM
    Test / fork := true,

    // Create temp directory for tests
    Test / testOptions += Tests.Setup { () =>
      val tmpDir = new java.io.File(testTempDir)
      if (!tmpDir.exists()) tmpDir.mkdirs()
    },

    // Test environment variables
    Test / envVars ++= {
      val baseEnvVars = Map(
        "SPARK_DIST_CLASSPATH" -> (Test / fullClasspath).value.files.map(_.getAbsolutePath)
          .mkString(java.io.File.pathSeparator),
        "SPARK_PREPEND_CLASSES" -> "1",
        "SPARK_SCALA_VERSION" -> scalaBinaryVersion.value,
        "SPARK_TESTING" -> "1",
        "JAVA_HOME" -> sys.env.getOrElse("JAVA_HOME", sys.props("java.home")),
        // Set SPARK_LOCAL_IP to avoid hostname resolution issues on macOS
        // This ensures Utils.localHostName() returns a bindable address
        "SPARK_LOCAL_IP" -> sys.env.getOrElse("SPARK_LOCAL_IP", "127.0.0.1"),
        // Beeline options for launcher tests
        "SPARK_BEELINE_OPTS" -> "-DmyKey=yourValue"
      )
      // macOS specific
      if (sys.props("os.name").contains("Mac OS X")) {
        baseEnvVars + ("OBJC_DISABLE_INITIALIZE_FORK_SAFETY" -> "YES")
      } else {
        baseEnvVars
      }
    },

    // Test JVM options
    Test / javaOptions ++= {
      val heapSize = sys.env.getOrElse("HEAP_SIZE", "4g")
      val metaspaceSize = sys.env.getOrElse("METASPACE_SIZE", "1300m")
      Seq(
        s"-Xmx$heapSize",
        "-Xss4m",
        s"-XX:MaxMetaspaceSize=$metaspaceSize",
        "-XX:ReservedCodeCacheSize=128m",
        s"-Djava.io.tmpdir=$testTempDir",
        s"-Dspark.test.home=$sparkHome",
        "-Dspark.testing=1",
        "-Dspark.port.maxRetries=100",
        "-Dspark.master.rest.enabled=false",
        "-Dspark.memory.debugFill=true",
        "-Dspark.ui.enabled=false",
        "-Dspark.ui.showConsoleProgress=false",
        "-Dspark.unsafe.exceptionOnMemoryLeak=true",
        "-Dspark.hadoop.hadoop.caller.context.enabled=true",
        // Only set bindAddress (not host) - host gets stored in checkpoints and breaks tests
        "-Dspark.driver.bindAddress=127.0.0.1",
        "-Dhive.conf.validation=false",
        "-Dsun.io.serialization.extendedDebugInfo=false",
        "-Dderby.system.durability=test",
        "-Dfile.encoding=UTF-8",
        "-ea"
      ) ++ extraTestJavaArgs
    },

    // Test output options
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    // Slowpoke notifications
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-W", "120", "300"),
    // Exclude test tags (e.g., ExtendedLevelDBTest on Apple Silicon)
    Test / testOptions ++= defaultExcludedTags.map(tag =>
      Tests.Argument(TestFrameworks.ScalaTest, "-l", tag)
    ),

    // JUnit interface
    libraryDependencies += Dependencies.TestDeps.jupiterInterface % Test,

    // Jetty dependencies - needed for tests (marked Provided in core for shading)
    libraryDependencies ++= Seq(
      Dependencies.Jetty.compressionServer % Test,
      Dependencies.Jetty.compressionCommon % Test,
      Dependencies.Jetty.compressionGzip % Test,
      Dependencies.Jetty.server % Test,
      Dependencies.Jetty.util % Test,
      Dependencies.Jetty.http % Test,
      Dependencies.Jetty.io % Test,
      Dependencies.Jetty.client % Test,
      Dependencies.Jetty.security % Test,
      Dependencies.Jetty.session % Test,
      Dependencies.Jetty.ee10Servlet % Test,
      Dependencies.Jetty.ee10Servlets % Test,
      Dependencies.Jetty.ee10Plus % Test,
      Dependencies.Jetty.ee10Proxy % Test,
      Dependencies.Google.guava % Test,
      Dependencies.Google.failureaccess % Test
    )
  )

  // ===== PUBLISHING SETTINGS =====
  // These settings prepare the build for potential future SBT-based publishing to Maven Central.
  // Currently, releases use Maven (dev/create-release/release-build.sh), but this infrastructure
  // enables future migration to SBT publishing via:
  // - sbt-ci-release: Automated CI publishing with git-based versioning (https://github.com/sbt/sbt-ci-release)
  // - sbt-pgp + Sonatype Central Portal: Manual control with explicit versioning (https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html)
  lazy val publishSettings: Seq[Setting[_]] = Seq(
    publishMavenStyle := true,
    publish / skip := false,

    // POM metadata required for Maven Central
    homepage := Some(url("https://spark.apache.org/")),
    licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/apache/spark"),
        "scm:git:git@github.com:apache/spark.git",
        Some("scm:git:https://gitbox.apache.org/repos/asf/spark.git")
      )
    ),

    // Don't include repository info in published POMs
    pomIncludeRepository := { _ => false }
  )

  // ===== COMMON SETTINGS =====
  lazy val commonSettings: Seq[Setting[_]] =
    resolverSettings ++
    scalaCompilerSettings ++
    javaCompilerSettings ++
    publishSettings ++
    Seq(
      organization := "org.apache.spark",
      version := Versions.spark,
      // Allow Scala version override via system property: -Dscala.version=3.3.4
      // Falls back to versions.properties if not specified
      scalaVersion := sys.props.getOrElse("scala.version", Versions.scala),

      // Export jars for compile, not for test
      Compile / exportJars := true,
      Test / exportJars := false,

      // Java home
      javaHome := {
        val envJavaHome = sys.env.get("JAVA_HOME")
        val propsJavaHome = sys.props.get("java.home")
        envJavaHome.orElse(propsJavaHome).map(file)
      },

      // Dependency exclusions applied to all projects
      excludeDependencies ++= Seq(
        ExclusionRule(organization = "org.codehaus.groovy", name = "groovy-all"),
        ExclusionRule(organization = "ch.qos.logback"),
        ExclusionRule(organization = "org.slf4j", name = "slf4j-simple")
      ),

      // Common dependency overrides for version alignment
      dependencyOverrides ++= Seq(
        Dependencies.Logging.slf4jApi,
        Dependencies.Google.guava,
        Dependencies.Google.gson,
        Dependencies.Avro.core,
        Dependencies.Misc.jline,
        Dependencies.Jackson.core,
        Dependencies.Jackson.databind,
        Dependencies.Jackson.annotations,
        Dependencies.Jackson.moduleScala
      )
    )

  // ===== MIMA SETTINGS =====
  // Settings for modules that should be checked for binary compatibility
  lazy val mimaSettings: Seq[Setting[_]] = MimaBuild.mimaSettings()

  // Settings for modules that skip MiMa (new modules, internal modules, etc.)
  lazy val skipMimaSettings: Seq[Setting[_]] = MimaBuild.skipMimaSettings

  // ===== COMBINED SETTINGS FOR MODULES =====
  lazy val sparkModuleSettings: Seq[Setting[_]] = commonSettings ++ testSettings ++ skipMimaSettings

  // Settings for modules with MiMa binary compatibility checking
  lazy val sparkModuleWithMimaSettings: Seq[Setting[_]] = commonSettings ++ testSettings ++ mimaSettings
}
