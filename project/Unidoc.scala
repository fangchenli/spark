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
import sbtunidoc.BaseUnidocPlugin
import sbtunidoc.JavaUnidocPlugin
import sbtunidoc.ScalaUnidocPlugin
import sbtunidoc.BaseUnidocPlugin.autoImport._
import sbtunidoc.GenJavadocPlugin.autoImport._
import sbtunidoc.JavaUnidocPlugin.autoImport._
import sbtunidoc.ScalaUnidocPlugin.autoImport._

/**
 * Unidoc settings for generating unified Scaladoc and Javadoc across all Spark modules.
 */
object Unidoc {

  val unidocSourceBase = settingKey[String]("Base URL of source links in Scaladoc.")

  /**
   * Filter out undocumented packages from the documentation.
   * These include internal packages, examples, and packages with special handling.
   */
  private def ignoreUndocumentedPackages(packages: Seq[Seq[File]]): Seq[Seq[File]] = {
    packages
      .map(_.filterNot(_.getName.contains("$")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/deploy")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/examples")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/internal")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/memory")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/network")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/rpc")))
      .map(_.filterNot(f =>
        f.getCanonicalPath.contains("org/apache/spark/shuffle") &&
        !f.getCanonicalPath.contains("org/apache/spark/shuffle/api")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/executor")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/ExecutorAllocationClient")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/scheduler/cluster/CoarseGrainedSchedulerBackend")))
      .map(_.filterNot(f =>
        f.getCanonicalPath.contains("org/apache/spark/unsafe") &&
        !f.getCanonicalPath.contains("org/apache/spark/unsafe/types/CalendarInterval")))
      .map(_.filterNot(_.getCanonicalPath.contains("python")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/util/collection")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/sql/catalyst")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/sql/execution")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/sql/internal")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/sql/hive/test")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/sql/hive/execution")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/hive")))
      .map(_.filterNot(_.getCanonicalPath.contains("org/apache/spark/sql/v2/avro")))
      .map(_.filterNot(_.getCanonicalPath.contains("SSLOptions")))
  }

  /**
   * Filter out classpaths that should not be included in documentation.
   */
  private def ignoreClasspaths(classpaths: Seq[Classpath]): Seq[Classpath] = {
    classpaths
      .map(_.filterNot(_.data.getCanonicalPath.matches(""".*kafka-clients-0\.10.*""")))
      .map(_.filterNot(_.data.getCanonicalPath.matches(""".*kafka_2\..*-0\.10.*""")))
      .map(_.filterNot(_.data.getCanonicalPath.contains("apache-rat")))
      .map(_.filterNot(_.data.getCanonicalPath.contains("connect-shims")))
  }

  /**
   * Settings for GenJavadoc plugin - generates Javadoc from Scala sources.
   */
  lazy val genjavadocSettings: Seq[Setting[_]] = Seq(
    scalacOptions ++= Seq(
      "-P:genjavadoc:strictVisibility=true" // hide package private types
    )
  )

  /**
   * Create unidoc settings for the root project.
   * This should be applied to the root aggregating project.
   *
   * @param excludedProjects Projects to exclude from documentation
   */
  def settings(excludedProjects: Seq[ProjectReference]): Seq[Setting[_]] = {
    BaseUnidocPlugin.projectSettings ++
    ScalaUnidocPlugin.projectSettings ++
    JavaUnidocPlugin.projectSettings ++
    Seq(
      publish := {},

      // Exclude from lint - this setting is used internally by genjavadoc plugin
      Global / excludeLintKeys += unidocGenjavadocVersion,
      unidocGenjavadocVersion := "0.19",

      (ScalaUnidoc / unidoc / unidocAllClasspaths) := {
        ignoreClasspaths((ScalaUnidoc / unidoc / unidocAllClasspaths).value)
      },

      (JavaUnidoc / unidoc / unidocAllClasspaths) := {
        ignoreClasspaths((JavaUnidoc / unidoc / unidocAllClasspaths).value)
      },

      // Skip packages that are not public API or contain quasiquotes which break scaladoc
      (ScalaUnidoc / unidoc / unidocAllSources) := {
        ignoreUndocumentedPackages((ScalaUnidoc / unidoc / unidocAllSources).value)
      },

      // Skip class names containing $ and some internal packages in Javadocs
      (JavaUnidoc / unidoc / unidocAllSources) := {
        ignoreUndocumentedPackages((JavaUnidoc / unidoc / unidocAllSources).value)
          .map(_.filterNot(_.getCanonicalPath.contains("org/apache/hadoop")))
      },

      (JavaUnidoc / unidoc / javacOptions) := {
        Seq(
          "-windowtitle", "Spark " + version.value.replaceAll("-SNAPSHOT", "") + " JavaDoc",
          "-public",
          "-noqualifier", "java.lang",
          "-tag", """example:a:Example\:""",
          "-tag", """note:a:Note\:""",
          "-tag", "group:X",
          "-tag", "tparam:X",
          "-tag", "constructor:X",
          "-tag", "todo:X",
          "-tag", "groupname:X",
          "-tag", "inheritdoc",
          "--ignore-source-errors", "-notree"
        )
      },

      // Use GitHub repository for Scaladoc source links
      unidocSourceBase := s"https://github.com/apache/spark/tree/v${version.value}",

      (ScalaUnidoc / unidoc / scalacOptions) ++= Seq(
        "-groups", // Group similar methods together based on the @group annotation
        "-skip-packages", "org.apache.hadoop",
        "-sourcepath", (ThisBuild / baseDirectory).value.getAbsolutePath
      ) ++ (
        // Add links to sources when generating Scaladoc for a non-snapshot release
        if (!isSnapshot.value) {
          Opts.doc.sourceUrl(unidocSourceBase.value + "€{FILE_PATH_EXT}")
        } else {
          Seq()
        }
      ),

      (ScalaUnidoc / unidoc / unidocProjectFilter) :=
        inAnyProject -- inProjects(excludedProjects: _*),

      (JavaUnidoc / unidoc / unidocProjectFilter) :=
        inAnyProject -- inProjects(excludedProjects: _*)
    )
  }
}
