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

  // Merge strategy for assembly
  lazy val coreMergeStrategy: String => sbtassembly.MergeStrategy = {
    case m if m.toLowerCase(Locale.ROOT).endsWith("manifest.mf") => MergeStrategy.discard
    case m if m.toLowerCase(Locale.ROOT).matches("meta-inf.*\\.sf$") => MergeStrategy.discard
    case m if m.toLowerCase(Locale.ROOT).startsWith("meta-inf/services/") => MergeStrategy.filterDistinctLines
    case m if m.toLowerCase(Locale.ROOT).endsWith(".proto") => MergeStrategy.discard
    case "log4j2.properties" => MergeStrategy.discard
    case "reference.conf" => MergeStrategy.concat
    case "git.properties" => MergeStrategy.discard
    case _ => MergeStrategy.first
  }

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

  lazy val connectClientAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := coreMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false)
  )

  lazy val protobufAssemblySettings: Seq[Setting[_]] = Seq(
    assembly / assemblyMergeStrategy := coreMergeStrategy,
    assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(false)
  )
}
