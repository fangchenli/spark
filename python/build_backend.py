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
Custom PEP 517 build backend for PySpark.

This module wraps setuptools.build_meta and handles the creation of symlinks
to JARs, scripts, and data files from the parent Spark directory before building.
"""

import ctypes
from pathlib import Path
from shutil import copyfile, copytree, rmtree

# Re-export everything from setuptools.build_meta
from setuptools.build_meta import *  # noqa: F401, F403
from setuptools.build_meta import build_wheel as _orig_build_wheel
from setuptools.build_meta import build_sdist as _orig_build_sdist
from setuptools.build_meta import (
    get_requires_for_build_wheel as _orig_get_requires_for_build_wheel,
    get_requires_for_build_sdist as _orig_get_requires_for_build_sdist,
    prepare_metadata_for_build_wheel as _orig_prepare_metadata_for_build_wheel,
)

# --- Config ---
TEMP_PATH = Path("deps")
SPARK_HOME = Path(__file__).parent.parent.resolve()

JARS_TARGET = TEMP_PATH / "jars"
SCRIPTS_TARGET = TEMP_PATH / "bin"
USER_SCRIPTS_TARGET = TEMP_PATH / "sbin"
EXAMPLES_TARGET = TEMP_PATH / "examples"
DATA_TARGET = TEMP_PATH / "data"
LICENSES_TARGET = TEMP_PATH / "licenses"


def _supports_symlinks():
    """Check if the system supports symlinks (e.g. *nix) or not."""
    return hasattr(Path, "symlink_to") and (
        (not hasattr(ctypes, "windll"))  # Non-Windows
        or (
            hasattr(ctypes.windll, "shell32")
            and hasattr(ctypes.windll.shell32, "IsUserAnAdmin")
            and bool(ctypes.windll.shell32.IsUserAnAdmin())
        )
    )


def _is_in_spark_source():
    """Check if we're building from within Spark source tree."""
    return (SPARK_HOME / "core/src/main/scala/org/apache/spark/SparkContext.scala").is_file() or (
        (SPARK_HOME / "RELEASE").is_file()
        and list((SPARK_HOME / "jars").glob("spark*core*.jar"))
    )


def _find_jars_path():
    """Find the path to Spark JARs."""
    # Check for development build (assembly/target/scala-*/jars/)
    jars_paths = list((SPARK_HOME / "assembly/target").glob("scala-*/jars/"))
    if len(jars_paths) == 1:
        return jars_paths[0]
    elif len(jars_paths) > 1:
        raise RuntimeError(
            f"Assembly jars exist for multiple Scala versions ({jars_paths}), "
            "please cleanup assembly/target"
        )

    # Check for release mode (jars/)
    if (SPARK_HOME / "RELEASE").is_file() and list((SPARK_HOME / "jars").glob("spark*core*.jar")):
        return SPARK_HOME / "jars"

    raise RuntimeError(
        "Could not find Spark JARs. If you are building from source, "
        "you must first build Spark:\n"
        "    ./build/mvn -DskipTests clean package"
    )


def _create_symlink_farm():
    """Create symlinks to Spark resources in deps/ directory."""
    # If deps already exists, assume it was created by an earlier hook
    if TEMP_PATH.exists():
        return

    jars_path = _find_jars_path()

    TEMP_PATH.mkdir()

    sources = {
        JARS_TARGET: jars_path,
        SCRIPTS_TARGET: SPARK_HOME / "bin",
        USER_SCRIPTS_TARGET: SPARK_HOME / "sbin",
        EXAMPLES_TARGET: SPARK_HOME / "examples/src/main/python",
        DATA_TARGET: SPARK_HOME / "data",
        LICENSES_TARGET: SPARK_HOME / "licenses",
    }

    if _supports_symlinks():
        for target, source in sources.items():
            target.symlink_to(source)
    else:
        for target, source in sources.items():
            copytree(source, target)


def _cleanup_symlink_farm():
    """Remove the symlink farm after building."""
    if not TEMP_PATH.exists():
        return

    targets = [
        JARS_TARGET,
        SCRIPTS_TARGET,
        USER_SCRIPTS_TARGET,
        EXAMPLES_TARGET,
        DATA_TARGET,
        LICENSES_TARGET,
    ]

    if _supports_symlinks():
        for target in targets:
            if target.is_symlink():
                target.unlink()
    else:
        for target in targets:
            if target.is_dir():
                rmtree(target)

    if TEMP_PATH.is_dir() and not list(TEMP_PATH.iterdir()):
        TEMP_PATH.rmdir()


def _copy_shell_script():
    """Copy shell.py to pyspark/python/pyspark for launcher scripts."""
    target_dir = Path("pyspark/python/pyspark")
    target_dir.mkdir(parents=True, exist_ok=True)
    copyfile("pyspark/shell.py", target_dir / "shell.py")


# --- PEP 517 Hook Overrides ---


def build_wheel(wheel_directory, config_settings=None, metadata_directory=None):
    """Build a wheel, creating symlink farm first if in Spark source tree."""
    in_spark = _is_in_spark_source()

    try:
        if in_spark:
            _create_symlink_farm()
        _copy_shell_script()
        return _orig_build_wheel(wheel_directory, config_settings, metadata_directory)
    finally:
        if in_spark:
            _cleanup_symlink_farm()


def build_sdist(sdist_directory, config_settings=None):
    """Build an sdist, creating symlink farm first if in Spark source tree."""
    in_spark = _is_in_spark_source()

    try:
        if in_spark:
            _create_symlink_farm()
        _copy_shell_script()
        return _orig_build_sdist(sdist_directory, config_settings)
    finally:
        if in_spark:
            _cleanup_symlink_farm()


def get_requires_for_build_wheel(config_settings=None):
    """Get build requirements, creating symlink farm first if needed."""
    if _is_in_spark_source():
        _create_symlink_farm()
    _copy_shell_script()
    return _orig_get_requires_for_build_wheel(config_settings)


def prepare_metadata_for_build_wheel(metadata_directory, config_settings=None):
    """Prepare wheel metadata, creating symlink farm first if needed."""
    if _is_in_spark_source():
        _create_symlink_farm()
    _copy_shell_script()
    return _orig_prepare_metadata_for_build_wheel(metadata_directory, config_settings)


def get_requires_for_build_sdist(config_settings=None):
    """Get sdist build requirements, creating symlink farm first if needed."""
    if _is_in_spark_source():
        _create_symlink_farm()
    _copy_shell_script()
    return _orig_get_requires_for_build_sdist(config_settings)
