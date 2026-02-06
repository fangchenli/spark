#!/usr/bin/env python3

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
Compare SBT and Maven builds to verify they produce equivalent artifacts.

This script helps validate the migration from sbt-pom-reader to native SBT
by comparing the JAR files and their contents produced by both build systems.

Usage:
    # Compare existing builds (fastest - assumes both builds already done)
    ./dev/compare-builds.py

    # Build with Maven first, then compare with existing SBT build
    ./dev/compare-builds.py --build-maven

    # Build with SBT first, then compare with existing Maven build
    ./dev/compare-builds.py --build-sbt

    # Build both and compare
    ./dev/compare-builds.py --build-both

    # Compare specific modules only
    ./dev/compare-builds.py --modules core,sql,catalyst

    # Show detailed class-level differences
    ./dev/compare-builds.py --verbose

    # Compare assembly JARs only
    ./dev/compare-builds.py --assemblies-only

    # Output report to file
    ./dev/compare-builds.py --output report.txt

    # Compare only JARs that exist in both builds
    ./dev/compare-builds.py --matching-only

    # Ignore expected shading differences (org/sparkproject/, etc.)
    ./dev/compare-builds.py --ignore-shaded

    # Best options for validation (ignore expected differences)
    ./dev/compare-builds.py --matching-only --ignore-shaded -v

    # Compare dependencies (slower, runs Maven/SBT commands)
    ./dev/compare-builds.py --deps

    # Compare shading/relocation in assembly JARs
    ./dev/compare-builds.py --shading

Notes:
    - Maven and SBT handle shading differently:
      - Maven includes shaded classes in the main JAR (spark-core)
      - SBT produces separate assembly JARs (spark-core-assembly)
    - Use --ignore-shaded to focus on actual source code differences
    - Use --matching-only when builds have different module coverage
"""

import argparse
import re
import subprocess
import sys
import zipfile
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple


# Get Spark home directory
SPARK_HOME = Path(__file__).parent.parent.resolve()


@dataclass
class JarInfo:
    """Information about a JAR file."""

    path: Path
    size: int
    classes: Set[str] = field(default_factory=set)
    resources: Set[str] = field(default_factory=set)
    meta_inf: Set[str] = field(default_factory=set)

    @property
    def name(self) -> str:
        return self.path.name

    def class_count(self) -> int:
        return len(self.classes)


@dataclass
class ComparisonResult:
    """Result of comparing two JARs."""

    maven_jar: Optional[JarInfo]
    sbt_jar: Optional[JarInfo]
    only_in_maven: Set[str] = field(default_factory=set)
    only_in_sbt: Set[str] = field(default_factory=set)
    size_diff_pct: float = 0.0

    @property
    def is_match(self) -> bool:
        return (
            self.maven_jar is not None
            and self.sbt_jar is not None
            and len(self.only_in_maven) == 0
            and len(self.only_in_sbt) == 0
        )

    @property
    def has_content_diff(self) -> bool:
        return len(self.only_in_maven) > 0 or len(self.only_in_sbt) > 0


def run_command(cmd: List[str], cwd: Path = SPARK_HOME) -> Tuple[int, str, str]:
    """Run a command and return exit code, stdout, stderr."""
    print(f"[cmd] {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    return result.returncode, result.stdout, result.stderr


def build_maven(profiles: List[str] = None) -> bool:
    """Build with Maven."""
    print("\n" + "=" * 72)
    print("Building with Maven...")
    print("=" * 72)

    cmd = [str(SPARK_HOME / "build" / "mvn"), "-DskipTests", "package"]
    if profiles:
        cmd.extend([f"-P{p}" for p in profiles])

    ret, stdout, stderr = run_command(cmd)
    if ret != 0:
        print(f"[error] Maven build failed:\n{stderr}")
        return False
    print("[ok] Maven build completed successfully")
    return True


def build_sbt() -> bool:
    """Build with SBT."""
    print("\n" + "=" * 72)
    print("Building with SBT...")
    print("=" * 72)

    cmd = [str(SPARK_HOME / "build" / "sbt"), "package"]
    ret, stdout, stderr = run_command(cmd)
    if ret != 0:
        print(f"[error] SBT build failed:\n{stderr}")
        return False
    print("[ok] SBT build completed successfully")
    return True


def get_jar_contents(jar_path: Path) -> JarInfo:
    """Extract information about a JAR file's contents."""
    info = JarInfo(path=jar_path, size=jar_path.stat().st_size)

    try:
        with zipfile.ZipFile(jar_path, "r") as zf:
            for name in zf.namelist():
                if name.endswith("/"):
                    continue  # Skip directories
                if name.endswith(".class"):
                    # Skip multi-release JAR entries (META-INF/versions/X/*.class)
                    # These are version-specific and may differ between builds
                    if name.startswith("META-INF/versions/"):
                        info.meta_inf.add(name)
                    else:
                        info.classes.add(name)
                elif name.startswith("META-INF/"):
                    info.meta_inf.add(name)
                else:
                    info.resources.add(name)
    except zipfile.BadZipFile:
        print(f"[warn] Could not read JAR: {jar_path}")

    return info


def should_skip_jar(name: str) -> bool:
    """Check if a JAR should be skipped from comparison."""
    # Skip Maven's pre-shaded "original-" JARs
    if name.startswith("original-"):
        return True
    # Skip assembly JARs (use --assemblies-only for those)
    if "-assembly" in name or name.endswith("-assembly.jar"):
        return True
    return False


def normalize_jar_name(name: str) -> str:
    """
    Normalize JAR name for comparison between Maven and SBT.

    Maven: spark-core_2.13-4.0.0-SNAPSHOT.jar
    SBT:   spark-core_2.13-4.0.0-SNAPSHOT.jar (should be same)

    Extract the artifact name (before version) for matching.
    Examples:
        spark-core_2.13-4.0.0-SNAPSHOT.jar -> spark-core_2.13
        spark-sql-kafka-0-10_2.13-4.0.0-SNAPSHOT.jar -> spark-sql-kafka-0-10_2.13
    """
    # Remove .jar extension
    base = name[:-4] if name.endswith(".jar") else name

    # Find version pattern: -X.Y.Z or -X.Y.Z-SNAPSHOT or similar
    # Version starts with a digit after a hyphen
    version_pattern = r"-(\d+\.\d+\.\d+.*?)$"
    match = re.search(version_pattern, base)
    if match:
        return base[: match.start()]
    return base


def find_maven_jars(modules: Optional[List[str]] = None) -> Dict[str, JarInfo]:
    """Find all JAR files from Maven build."""
    jars = {}

    # Maven puts JARs in module/target/
    for target_dir in SPARK_HOME.rglob("target"):
        # Skip SBT's scala-versioned directories
        if "/scala-" in str(target_dir):
            continue
        # Skip test-classes and other non-artifact directories
        if "test-classes" in str(target_dir) or "classes" == target_dir.name:
            continue

        for jar_path in target_dir.glob("*.jar"):
            # Skip test JARs, sources, javadocs
            if "-tests.jar" in jar_path.name or "-sources.jar" in jar_path.name:
                continue
            if "-javadoc.jar" in jar_path.name:
                continue

            # Skip JARs that should be excluded from regular comparison
            if should_skip_jar(jar_path.name):
                continue

            # Filter by module if specified
            if modules:
                module_match = False
                for m in modules:
                    if m in str(jar_path):
                        module_match = True
                        break
                if not module_match:
                    continue

            norm_name = normalize_jar_name(jar_path.name)
            jars[norm_name] = get_jar_contents(jar_path)

    return jars


def find_sbt_jars(modules: Optional[List[str]] = None) -> Dict[str, JarInfo]:
    """Find all JAR files from SBT build."""
    jars = {}

    # SBT puts JARs in module/target/scala-X.XX/
    for scala_dir in SPARK_HOME.rglob("target/scala-*"):
        if not scala_dir.is_dir():
            continue

        for jar_path in scala_dir.glob("*.jar"):
            # Skip test JARs and sources
            if "-tests.jar" in jar_path.name or "-sources.jar" in jar_path.name:
                continue
            if "-javadoc.jar" in jar_path.name:
                continue

            # Skip JARs that should be excluded from regular comparison
            if should_skip_jar(jar_path.name):
                continue

            # Filter by module if specified
            if modules:
                module_match = False
                for m in modules:
                    if m in str(jar_path):
                        module_match = True
                        break
                if not module_match:
                    continue

            norm_name = normalize_jar_name(jar_path.name)
            jars[norm_name] = get_jar_contents(jar_path)

    return jars


# Packages that are shaded and expected to differ between Maven and SBT
SHADED_PACKAGES = {
    "org/sparkproject/",  # Shaded Jetty, Guava, etc. in Maven core
    "org/apache/spark/unused/",  # Placeholder classes
}


def is_shaded_class(class_name: str) -> bool:
    """Check if a class is from a shaded package."""
    return any(class_name.startswith(pkg) for pkg in SHADED_PACKAGES)


def _format_size_diff(maven_size: int, sbt_size: int) -> str:
    """Format size difference in a human-readable way."""
    if maven_size == sbt_size:
        return "identical"
    bigger, smaller = max(maven_size, sbt_size), min(maven_size, sbt_size)
    if smaller == 0:
        return "N/A (one side is empty)"
    ratio = bigger / smaller
    label = "Maven" if maven_size > sbt_size else "SBT"
    if ratio >= 2:
        return f"{label} is {ratio:.0f}x larger"
    else:
        pct = (bigger - smaller) / smaller * 100
        return f"{label} is {pct:.1f}% larger"


def compare_jars(
    maven_jars: Dict[str, JarInfo],
    sbt_jars: Dict[str, JarInfo],
    matching_only: bool = False,
    ignore_shaded: bool = False,
) -> Dict[str, ComparisonResult]:
    """Compare JAR files from both builds."""
    results = {}

    if matching_only:
        all_names = set(maven_jars.keys()) & set(sbt_jars.keys())
    else:
        all_names = set(maven_jars.keys()) | set(sbt_jars.keys())

    for name in sorted(all_names):
        maven_jar = maven_jars.get(name)
        sbt_jar = sbt_jars.get(name)

        result = ComparisonResult(maven_jar=maven_jar, sbt_jar=sbt_jar)

        if maven_jar and sbt_jar:
            # Compare classes
            maven_classes = maven_jar.classes
            sbt_classes = sbt_jar.classes

            # Filter out shaded classes if requested
            if ignore_shaded:
                maven_classes = {c for c in maven_classes if not is_shaded_class(c)}
                sbt_classes = {c for c in sbt_classes if not is_shaded_class(c)}

            result.only_in_maven = maven_classes - sbt_classes
            result.only_in_sbt = sbt_classes - maven_classes

            # Calculate size difference as percentage of the smaller JAR
            min_size = min(maven_jar.size, sbt_jar.size)
            if min_size > 0:
                result.size_diff_pct = (
                    abs(maven_jar.size - sbt_jar.size) / min_size * 100
                )

        results[name] = result

    return results


def print_report(
    results: Dict[str, ComparisonResult],
    verbose: bool = False,
    output_file: Optional[Path] = None,
):
    """Print comparison report."""
    lines = []

    def out(msg: str = ""):
        lines.append(msg)
        print(msg)

    out("\n" + "=" * 72)
    out("BUILD COMPARISON REPORT")
    out("=" * 72)

    # Summary statistics
    total = len(results)
    matches = sum(1 for r in results.values() if r.is_match)
    only_maven = sum(1 for r in results.values() if r.maven_jar and not r.sbt_jar)
    only_sbt = sum(1 for r in results.values() if r.sbt_jar and not r.maven_jar)
    content_diffs = sum(1 for r in results.values() if r.has_content_diff)

    out(f"\nTotal JARs compared: {total}")
    out(f"  Matching:          {matches}")
    out(f"  Only in Maven:     {only_maven}")
    out(f"  Only in SBT:       {only_sbt}")
    out(f"  Content differs:   {content_diffs}")

    # JARs only in Maven
    if only_maven > 0:
        out("\n" + "-" * 72)
        out("JARs only in Maven build:")
        out("-" * 72)
        for name, r in sorted(results.items()):
            if r.maven_jar and not r.sbt_jar:
                out(f"  {r.maven_jar.path.relative_to(SPARK_HOME)}")

    # JARs only in SBT
    if only_sbt > 0:
        out("\n" + "-" * 72)
        out("JARs only in SBT build:")
        out("-" * 72)
        for name, r in sorted(results.items()):
            if r.sbt_jar and not r.maven_jar:
                out(f"  {r.sbt_jar.path.relative_to(SPARK_HOME)}")

    # JARs with content differences
    if content_diffs > 0:
        out("\n" + "-" * 72)
        out("JARs with content differences:")
        out("-" * 72)
        for name, r in sorted(results.items()):
            if r.has_content_diff:
                out(f"\n  {name}")
                out(
                    f"    Maven: {r.maven_jar.class_count()} classes, {r.maven_jar.size:,} bytes"
                )
                out(
                    f"    SBT:   {r.sbt_jar.class_count()} classes, {r.sbt_jar.size:,} bytes"
                )
                out(f"    Size diff: {_format_size_diff(r.maven_jar.size, r.sbt_jar.size)}")
                out(f"    Classes only in Maven: {len(r.only_in_maven)}")
                out(f"    Classes only in SBT:   {len(r.only_in_sbt)}")

                if verbose:
                    if r.only_in_maven:
                        out("    Only in Maven:")
                        for cls in sorted(r.only_in_maven)[:10]:
                            out(f"      - {cls}")
                        if len(r.only_in_maven) > 10:
                            out(f"      ... and {len(r.only_in_maven) - 10} more")

                    if r.only_in_sbt:
                        out("    Only in SBT:")
                        for cls in sorted(r.only_in_sbt)[:10]:
                            out(f"      - {cls}")
                        if len(r.only_in_sbt) > 10:
                            out(f"      ... and {len(r.only_in_sbt) - 10} more")

    # Matching JARs (show size comparison)
    if matches > 0 and verbose:
        out("\n" + "-" * 72)
        out("Matching JARs (with size comparison):")
        out("-" * 72)
        for name, r in sorted(results.items()):
            if r.is_match:
                out(f"  {name}")
                out(f"    Maven: {r.maven_jar.size:,} bytes")
                out(f"    SBT:   {r.sbt_jar.size:,} bytes")
                if r.size_diff_pct > 1:
                    out(f"    Size diff: {_format_size_diff(r.maven_jar.size, r.sbt_jar.size)}")

    # Final verdict
    out("\n" + "=" * 72)
    if matches == total and total > 0:
        out("RESULT: All JARs match!")
    elif total == 0:
        out("RESULT: No JARs found to compare. Did you build first?")
    else:
        diff_names = sorted(
            name for name, r in results.items() if not r.is_match
        )
        out(f"RESULT: {total - matches} discrepancies found")
        for name in diff_names:
            r = results[name]
            reason = []
            if r.only_in_maven:
                reason.append(f"{len(r.only_in_maven)} classes only in Maven")
            if r.only_in_sbt:
                reason.append(f"{len(r.only_in_sbt)} classes only in SBT")
            if not reason:
                reason.append("class content differs")
            out(f"  - {name}: {', '.join(reason)}")
    out("=" * 72)

    # Write to file if requested
    if output_file:
        output_file.write_text("\n".join(lines))
        print(f"\nReport written to: {output_file}")


def find_assembly_jars() -> Tuple[List[Path], List[Path]]:
    """Find assembly JARs specifically."""
    maven_assemblies = []
    sbt_assemblies = []

    # Maven assembly location
    maven_assembly_dir = SPARK_HOME / "assembly" / "target"
    if maven_assembly_dir.exists():
        for jar in maven_assembly_dir.glob("*.jar"):
            if "assembly" in jar.name and "-tests" not in jar.name:
                maven_assemblies.append(jar)

    # SBT assembly location
    for scala_dir in (SPARK_HOME / "assembly" / "target").glob("scala-*"):
        for jar in scala_dir.glob("*.jar"):
            if "assembly" in jar.name and "-tests" not in jar.name:
                sbt_assemblies.append(jar)

    return maven_assemblies, sbt_assemblies


def compare_assembly_contents(
    maven_jar: Path, sbt_jar: Path, verbose: bool = False
) -> None:
    """Compare contents of assembly JARs in detail."""
    print("\n" + "=" * 72)
    print("ASSEMBLY JAR COMPARISON")
    print("=" * 72)

    maven_info = get_jar_contents(maven_jar)
    sbt_info = get_jar_contents(sbt_jar)

    print(f"\nMaven assembly: {maven_jar.name}")
    print(f"  Size: {maven_info.size:,} bytes")
    print(f"  Classes: {len(maven_info.classes)}")
    print(f"  Resources: {len(maven_info.resources)}")

    print(f"\nSBT assembly: {sbt_jar.name}")
    print(f"  Size: {sbt_info.size:,} bytes")
    print(f"  Classes: {len(sbt_info.classes)}")
    print(f"  Resources: {len(sbt_info.resources)}")

    # Compare class packages
    maven_packages = defaultdict(int)
    sbt_packages = defaultdict(int)

    for cls in maven_info.classes:
        pkg = "/".join(cls.split("/")[:-1])
        maven_packages[pkg] += 1

    for cls in sbt_info.classes:
        pkg = "/".join(cls.split("/")[:-1])
        sbt_packages[pkg] += 1

    all_packages = set(maven_packages.keys()) | set(sbt_packages.keys())

    print(f"\nPackage comparison ({len(all_packages)} packages):")

    only_maven_pkgs = set(maven_packages.keys()) - set(sbt_packages.keys())
    only_sbt_pkgs = set(sbt_packages.keys()) - set(maven_packages.keys())

    if only_maven_pkgs:
        print(f"\n  Packages only in Maven ({len(only_maven_pkgs)}):")
        for pkg in sorted(only_maven_pkgs)[:20]:
            print(f"    - {pkg} ({maven_packages[pkg]} classes)")
        if len(only_maven_pkgs) > 20:
            print(f"    ... and {len(only_maven_pkgs) - 20} more")

    if only_sbt_pkgs:
        print(f"\n  Packages only in SBT ({len(only_sbt_pkgs)}):")
        for pkg in sorted(only_sbt_pkgs)[:20]:
            print(f"    - {pkg} ({sbt_packages[pkg]} classes)")
        if len(only_sbt_pkgs) > 20:
            print(f"    ... and {len(only_sbt_pkgs) - 20} more")

    # Check for shading differences
    print("\n  Shading verification:")
    shaded_prefixes = ["org/sparkproject/", "org/apache/spark/unused/"]

    for prefix in shaded_prefixes:
        maven_count = sum(1 for c in maven_info.classes if c.startswith(prefix))
        sbt_count = sum(1 for c in sbt_info.classes if c.startswith(prefix))
        status = "[ok]" if maven_count == sbt_count else "[DIFF]"
        print(f"    {status} {prefix}: Maven={maven_count}, SBT={sbt_count}")


# ============================================================================
# SHADING COMPARISON
# ============================================================================

# Expected shading relocations for different assembly types
SHADING_RULES = {
    "core": {
        # Original package -> Shaded package
        "org/eclipse/jetty/": "org/sparkproject/jetty/",
        "com/google/common/": "org/sparkproject/guava/",
        "com/google/thirdparty/": "org/sparkproject/guava/",
        "com/google/protobuf/": "org/sparkproject/spark_core/protobuf/",
    },
    "connect-client": {
        "com/google/common/": "org/sparkproject/connect/guava/",
        "com/google/thirdparty/": "org/sparkproject/connect/guava/",
        "com/google/protobuf/": "org/sparkproject/com/google/protobuf/",
        "io/grpc/": "org/sparkproject/io/grpc/",
        "io/netty/": "org/sparkproject/io/netty/",
        "org/apache/arrow/": "org/sparkproject/org/apache/arrow/",
    },
}


def find_all_assembly_jars(build_type: str) -> Dict[str, Path]:
    """Find all assembly JARs for a specific build type (maven or sbt)."""
    assemblies = {}

    # Define where to look for each assembly type
    assembly_locations = [
        ("core", "core/target"),
        ("connect-client-jvm", "sql/connect/client/jvm/target"),
        ("connect-client-jdbc", "sql/connect/client/jdbc/target"),
        ("connect-server", "sql/connect/server/target"),
        ("kafka-sql", "connector/kafka-0-10-sql/target"),
        ("kafka-streaming", "connector/kafka-0-10/target"),
        ("kinesis", "connector/kinesis-asl/target"),
        ("protobuf", "connector/protobuf/target"),
    ]

    for name, base_path in assembly_locations:
        target_dir = SPARK_HOME / base_path

        if build_type == "maven":
            # Maven puts assemblies directly in target/
            for jar in target_dir.glob("*-assembly*.jar"):
                if "-tests" not in jar.name:
                    assemblies[name] = jar
                    break
        else:
            # SBT puts assemblies in target/scala-X.XX/
            for scala_dir in target_dir.glob("scala-*"):
                for jar in scala_dir.glob("*-assembly*.jar"):
                    if "-tests" not in jar.name:
                        assemblies[name] = jar
                        break

    return assemblies


def analyze_shading(jar_path: Path) -> Dict[str, Dict[str, int]]:
    """Analyze shading in a JAR file, returning package counts."""
    result = {
        "unshaded": defaultdict(int),  # Original packages that should be shaded
        "shaded": defaultdict(int),  # Properly shaded packages
    }

    # Packages that indicate improper shading (should have been relocated)
    unshaded_patterns = [
        "org/eclipse/jetty/",
        "com/google/common/",
        "com/google/thirdparty/",
        "com/google/protobuf/",
        "io/grpc/",
        "io/netty/",
        "org/apache/arrow/",
    ]

    # Shaded package prefixes
    shaded_patterns = [
        "org/sparkproject/",
    ]

    try:
        with zipfile.ZipFile(jar_path, "r") as zf:
            for name in zf.namelist():
                if not name.endswith(".class"):
                    continue

                # Check for unshaded (problematic) packages
                for pattern in unshaded_patterns:
                    if name.startswith(pattern):
                        pkg = pattern.rstrip("/")
                        result["unshaded"][pkg] += 1
                        break

                # Check for properly shaded packages
                for pattern in shaded_patterns:
                    if name.startswith(pattern):
                        # Extract the shaded sub-package (first 2 levels for detail)
                        rest = name[len(pattern) :]
                        parts = rest.split("/")
                        if len(parts) >= 2:
                            sub_pkg = "/".join(parts[:2])
                        else:
                            sub_pkg = parts[0] if parts else ""
                        full_pkg = pattern + sub_pkg
                        result["shaded"][full_pkg] += 1
                        break

    except zipfile.BadZipFile:
        print(f"[warn] Could not read JAR: {jar_path}")

    return result


def compare_shading(verbose: bool = False) -> None:
    """Compare shading between Maven and SBT builds."""
    print("\n" + "=" * 72)
    print("SHADING COMPARISON")
    print("=" * 72)

    # Find assemblies from both builds
    print("\nSearching for Maven assemblies...")
    maven_assemblies = find_all_assembly_jars("maven")
    print(
        f"Found {len(maven_assemblies)} Maven assemblies: {list(maven_assemblies.keys())}"
    )

    print("\nSearching for SBT assemblies...")
    sbt_assemblies = find_all_assembly_jars("sbt")
    print(f"Found {len(sbt_assemblies)} SBT assemblies: {list(sbt_assemblies.keys())}")

    if not maven_assemblies and not sbt_assemblies:
        print("\n[error] No assembly JARs found. Build assemblies first.")
        return

    # Compare each assembly type
    all_names = set(maven_assemblies.keys()) | set(sbt_assemblies.keys())
    issues_found = 0

    for name in sorted(all_names):
        print(f"\n{'-' * 72}")
        print(f"Assembly: {name}")
        print(f"{'-' * 72}")

        maven_jar = maven_assemblies.get(name)
        sbt_jar = sbt_assemblies.get(name)

        # Handle single-build analysis
        if not maven_jar and not sbt_jar:
            print("  [warn] No assemblies found")
            continue

        if maven_jar:
            print(f"  Maven: {maven_jar.name} ({maven_jar.stat().st_size:,} bytes)")
        else:
            print("  Maven: (not built)")

        if sbt_jar:
            print(f"  SBT:   {sbt_jar.name} ({sbt_jar.stat().st_size:,} bytes)")
        else:
            print("  SBT:   (not built)")

        # Analyze shading in available builds
        maven_shading = analyze_shading(maven_jar) if maven_jar else None
        sbt_shading = analyze_shading(sbt_jar) if sbt_jar else None

        # Check for unshaded classes (problems)
        print("\n  Unshaded packages (should be empty):")
        maven_unshaded = dict(maven_shading["unshaded"]) if maven_shading else {}
        sbt_unshaded = dict(sbt_shading["unshaded"]) if sbt_shading else {}

        if not maven_unshaded and not sbt_unshaded:
            print("    [ok] No unshaded classes found")
        else:
            if maven_unshaded:
                print("    Maven unshaded (PROBLEM):")
                for pkg, count in sorted(maven_unshaded.items()):
                    print(f"      - {pkg}: {count} classes")
                    issues_found += 1
            if sbt_unshaded:
                print("    SBT unshaded (PROBLEM):")
                for pkg, count in sorted(sbt_unshaded.items()):
                    print(f"      - {pkg}: {count} classes")
                    issues_found += 1

        # Compare/show shaded packages
        print("\n  Shaded packages:")
        maven_shaded = dict(maven_shading["shaded"]) if maven_shading else {}
        sbt_shaded = dict(sbt_shading["shaded"]) if sbt_shading else {}

        all_shaded_pkgs = set(maven_shaded.keys()) | set(sbt_shaded.keys())

        if not all_shaded_pkgs:
            print("    (no shaded packages found)")
        else:
            for pkg in sorted(all_shaded_pkgs):
                m_count = maven_shaded.get(pkg, 0)
                s_count = sbt_shaded.get(pkg, 0)

                # Format based on what's available
                if maven_jar and sbt_jar:
                    if m_count == s_count:
                        status = "[ok]"
                    elif m_count == 0:
                        status = "[SBT only]"
                    elif s_count == 0:
                        status = "[Maven only]"
                    else:
                        status = "[DIFF]"
                        issues_found += 1
                    print(f"    {status} {pkg}: Maven={m_count}, SBT={s_count}")
                elif sbt_jar:
                    print(f"    [ok] {pkg}: {s_count} classes")
                else:
                    print(f"    [ok] {pkg}: {m_count} classes")

    # Summary
    print("\n" + "=" * 72)
    if issues_found == 0:
        print("RESULT: Shading verification passed!")
    else:
        print(f"RESULT: {issues_found} shading issues found")
    print("=" * 72)


def get_maven_dependencies() -> Dict[str, Set[str]]:
    """Get dependencies for each module from Maven."""
    deps = {}
    cmd = [
        str(SPARK_HOME / "build" / "mvn"),
        "dependency:list",
        "-DoutputAbsoluteArtifactFilename=false",
        "-DincludeScope=compile",
        "-q",
    ]
    ret, stdout, stderr = run_command(cmd)
    if ret != 0:
        print(f"[warn] Failed to get Maven dependencies: {stderr}")
        return deps

    current_module = None
    for line in stdout.split("\n"):
        # Look for module headers
        if line.startswith("[INFO] --- maven-dependency-plugin"):
            # Extract module from path
            match = re.search(r"@ (\S+) ---", line)
            if match:
                current_module = match.group(1)
                deps[current_module] = set()
        elif current_module and ":" in line and line.strip().startswith("[INFO]"):
            # Parse dependency line
            parts = line.strip().split()
            if len(parts) >= 2:
                dep = parts[1]  # groupId:artifactId:type:version:scope
                if ":" in dep:
                    deps[current_module].add(dep)

    return deps


def get_sbt_dependencies() -> Dict[str, Set[str]]:
    """Get dependencies for each module from SBT."""
    deps = {}
    # Use SBT's dependencyList task
    cmd = [str(SPARK_HOME / "build" / "sbt"), "dependencyList"]
    ret, stdout, stderr = run_command(cmd)
    if ret != 0:
        print(f"[warn] Failed to get SBT dependencies: {stderr}")
        return deps

    current_module = None
    for line in stdout.split("\n"):
        # Look for project headers in SBT output
        if line.startswith("[info] ") and "/" in line and "dependencyList" not in line:
            module_match = re.search(r"\[info\] (\S+) /", line)
            if module_match:
                current_module = module_match.group(1)
                deps[current_module] = set()
        elif current_module and line.strip() and not line.startswith("["):
            # SBT dependency format: groupId:artifactId:version
            dep = line.strip()
            if ":" in dep and not dep.startswith("#"):
                deps[current_module].add(dep)

    return deps


def compare_dependencies(verbose: bool = False) -> None:
    """Compare dependencies between Maven and SBT builds."""
    print("\n" + "=" * 72)
    print("DEPENDENCY COMPARISON")
    print("=" * 72)

    print("\nFetching Maven dependencies...")
    maven_deps = get_maven_dependencies()
    print(f"Found {len(maven_deps)} Maven modules with dependencies")

    print("\nFetching SBT dependencies...")
    sbt_deps = get_sbt_dependencies()
    print(f"Found {len(sbt_deps)} SBT modules with dependencies")

    if not maven_deps or not sbt_deps:
        print("\n[warn] Could not compare dependencies - missing data from one build")
        return

    # Compare common modules
    common_modules = set(maven_deps.keys()) & set(sbt_deps.keys())
    print(f"\nComparing {len(common_modules)} common modules...")

    differences = 0
    for module in sorted(common_modules):
        maven_set = maven_deps[module]
        sbt_set = sbt_deps[module]

        only_maven = maven_set - sbt_set
        only_sbt = sbt_set - maven_set

        if only_maven or only_sbt:
            differences += 1
            print(f"\n  {module}:")
            if only_maven:
                print(f"    Only in Maven ({len(only_maven)}):")
                for dep in sorted(only_maven)[:5]:
                    print(f"      - {dep}")
                if len(only_maven) > 5:
                    print(f"      ... and {len(only_maven) - 5} more")
            if only_sbt:
                print(f"    Only in SBT ({len(only_sbt)}):")
                for dep in sorted(only_sbt)[:5]:
                    print(f"      - {dep}")
                if len(only_sbt) > 5:
                    print(f"      ... and {len(only_sbt) - 5} more")

    if differences == 0:
        print("\n[ok] All common modules have matching dependencies!")
    else:
        print(f"\n[warn] {differences} modules have dependency differences")


def main():
    parser = argparse.ArgumentParser(
        description="Compare SBT and Maven builds for Spark",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--build-maven", action="store_true", help="Build with Maven before comparing"
    )
    parser.add_argument(
        "--build-sbt", action="store_true", help="Build with SBT before comparing"
    )
    parser.add_argument(
        "--build-both",
        action="store_true",
        help="Build with both Maven and SBT before comparing",
    )
    parser.add_argument(
        "--modules",
        type=str,
        help="Comma-separated list of modules to compare (e.g., core,sql,catalyst)",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Show detailed class-level differences",
    )
    parser.add_argument(
        "--assemblies-only", action="store_true", help="Compare assembly JARs only"
    )
    parser.add_argument("--output", "-o", type=str, help="Write report to file")
    parser.add_argument(
        "--maven-profiles",
        type=str,
        default="",
        help="Maven profiles to use (comma-separated, e.g., hive,yarn)",
    )
    parser.add_argument(
        "--matching-only",
        action="store_true",
        help="Only compare JARs that exist in both builds",
    )
    parser.add_argument(
        "--ignore-shaded",
        action="store_true",
        help="Ignore shaded package differences (org/sparkproject/, etc.)",
    )
    parser.add_argument(
        "--deps",
        action="store_true",
        help="Compare dependencies (slower, requires running Maven/SBT)",
    )
    parser.add_argument(
        "--shading",
        action="store_true",
        help="Compare shading/relocation in assembly JARs",
    )

    args = parser.parse_args()

    # Parse modules
    modules = None
    if args.modules:
        modules = [m.strip() for m in args.modules.split(",")]

    # Parse Maven profiles
    maven_profiles = None
    if args.maven_profiles:
        maven_profiles = [p.strip() for p in args.maven_profiles.split(",")]

    # Build if requested
    if args.build_both:
        if not build_maven(maven_profiles):
            sys.exit(1)
        if not build_sbt():
            sys.exit(1)
    elif args.build_maven:
        if not build_maven(maven_profiles):
            sys.exit(1)
    elif args.build_sbt:
        if not build_sbt():
            sys.exit(1)

    # Dependency comparison mode
    if args.deps:
        compare_dependencies(args.verbose)
        return

    # Shading comparison mode
    if args.shading:
        compare_shading(args.verbose)
        return

    # Assembly comparison mode
    if args.assemblies_only:
        maven_assemblies, sbt_assemblies = find_assembly_jars()
        if not maven_assemblies:
            print("[error] No Maven assembly JARs found")
            sys.exit(1)
        if not sbt_assemblies:
            print("[error] No SBT assembly JARs found")
            sys.exit(1)

        compare_assembly_contents(maven_assemblies[0], sbt_assemblies[0], args.verbose)
        return

    # Find JARs
    print("\nSearching for Maven JARs...")
    maven_jars = find_maven_jars(modules)
    print(f"Found {len(maven_jars)} Maven JARs")

    print("\nSearching for SBT JARs...")
    sbt_jars = find_sbt_jars(modules)
    print(f"Found {len(sbt_jars)} SBT JARs")

    if not maven_jars and not sbt_jars:
        print("\n[error] No JARs found. Please build first with --build-both")
        sys.exit(1)

    # Compare
    results = compare_jars(
        maven_jars,
        sbt_jars,
        matching_only=args.matching_only,
        ignore_shaded=args.ignore_shaded,
    )

    # Print report
    output_file = Path(args.output) if args.output else None
    print_report(results, args.verbose, output_file)

    # Exit with error if there are discrepancies
    matches = sum(1 for r in results.values() if r.is_match)
    if matches != len(results):
        sys.exit(1)


if __name__ == "__main__":
    main()
