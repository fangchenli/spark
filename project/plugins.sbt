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

// Load versions from versions.properties - single source of truth
val pluginVersions: Map[String, String] = {
  val props = new java.util.Properties()
  val propsFile = new java.io.File("versions.properties")
  if (propsFile.exists()) {
    val in = new java.io.FileInputStream(propsFile)
    try {
      props.load(in)
    } finally {
      in.close()
    }
  } else {
    throw new RuntimeException(
      s"versions.properties not found at ${propsFile.getAbsolutePath}. " +
      "This file is required for the SBT build.")
  }
  import scala.jdk.CollectionConverters._
  props.asScala.toMap
}

def getVersion(key: String): String = {
  pluginVersions.getOrElse(key,
    throw new RuntimeException(s"Property '$key' not found in versions.properties"))
}

addSbtPlugin("software.purpledragon" % "sbt-checkstyle-plugin" % getVersion("sbt.checkstyle.version"))

// If you are changing the dependency setting for checkstyle plugin,
// please check pom.xml in the root of the source tree too.
libraryDependencies += "com.puppycrawl.tools" % "checkstyle" % getVersion("checkstyle.version")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % getVersion("sbt.assembly.version"))

addSbtPlugin("com.github.sbt" % "sbt-eclipse" % getVersion("sbt.eclipse.version"))

addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % getVersion("scalastyle.sbt.version"))

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % getVersion("sbt.mima.version"))

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % getVersion("sbt.unidoc.version"))

addSbtPlugin("io.spray" % "sbt-revolver" % getVersion("sbt.revolver.version"))

libraryDependencies += "org.ow2.asm"  % "asm" % getVersion("asm.version")

libraryDependencies += "org.ow2.asm"  % "asm-commons" % getVersion("asm.version")

addSbtPlugin("com.simplytyped" % "sbt-antlr4" % getVersion("sbt.antlr4.version"))

// sbt-pom-reader removed - using native SBT build configuration
// addSbtPlugin("com.github.sbt" % "sbt-pom-reader" % "2.5.0")

addSbtPlugin("com.github.sbt.junit" % "sbt-jupiter-interface" % getVersion("sbt.jupiter.interface.version"))

addSbtPlugin("com.thesamet" % "sbt-protoc" % getVersion("sbt.protoc.version"))

addSbtPlugin("com.here.platform" % "sbt-bom" % getVersion("sbt.bom.version"))

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % getVersion("sbt.bloop.version"))

// ===== PUBLISHING TO MAVEN CENTRAL (future) =====
// Currently, releases use Maven (dev/create-release/release-build.sh).
// Two options for future SBT-based publishing:
//
// Option 1: sbt-ci-release (recommended for CI automation)
// - Bundles sbt-pgp, sbt-dynver (git-based versioning), and sbt-git
// - Automatic snapshot/release publishing based on git tags
// - See: https://github.com/sbt/sbt-ci-release
// addSbtPlugin("com.github.sbt" % "sbt-ci-release" % getVersion("sbt.ci.release.version"))
//
// Option 2: Manual with sbt-pgp (more control, explicit versioning)
// - Use: sbt publishSigned sonaUpload sonaRelease
// - See: https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html
// addSbtPlugin("com.github.sbt" % "sbt-pgp" % getVersion("sbt.pgp.version"))
