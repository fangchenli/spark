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
JVM Gateway for PySpark.

This module handles launching and connecting to the JVM process that PySpark
communicates with. It supports two backends:

- **Py4J** (default): Uses TCP sockets for Python-JVM communication
- **Gatun**: Uses shared memory for faster communication (set PYSPARK_USE_GATUN=true)
"""

import atexit
import os
import platform
import shlex
import shutil
import signal
import tempfile
import time
from subprocess import PIPE, Popen

from pyspark.errors import PySparkRuntimeError
from pyspark.find_spark_home import _find_spark_home
from pyspark.serializers import UTF8Deserializer, read_int

# for backward compatibility references.
from pyspark.util import local_connect_and_auth  # noqa: F401


# =============================================================================
# Backend Selection
# =============================================================================

def _use_gatun() -> bool:
    """Check if Gatun backend should be used."""
    return os.environ.get("PYSPARK_USE_GATUN", "").lower() in ("true", "1", "yes")


def _import_gateway_classes():
    """Import gateway classes from the appropriate backend.

    Returns a tuple of (java_import, JavaGateway, JavaObject, GatewayParameters,
                        ClientServer, JavaParameters, PythonParameters)
    """
    if _use_gatun():
        try:
            from gatun.py4j_compat import (
                ClientServer,
                GatewayParameters,
                JavaGateway,
                JavaObject,
                JavaParameters,
                PythonParameters,
                java_import,
            )
            return (java_import, JavaGateway, JavaObject, GatewayParameters,
                    ClientServer, JavaParameters, PythonParameters, True)
        except ImportError:
            # Gatun not installed (e.g., in worker process) - fall back to Py4J
            # Workers don't actually use the gateway, so this is fine
            pass

    from py4j.clientserver import ClientServer, JavaParameters, PythonParameters
    from py4j.java_gateway import (
        GatewayParameters,
        JavaGateway,
        JavaObject,
        java_import,
    )
    return (java_import, JavaGateway, JavaObject, GatewayParameters,
            ClientServer, JavaParameters, PythonParameters, False)


# Import gateway classes at module level
(java_import, JavaGateway, JavaObject, GatewayParameters,
 ClientServer, JavaParameters, PythonParameters, _USING_GATUN) = _import_gateway_classes()


# =============================================================================
# Classpath Helpers
# =============================================================================

def _get_spark_classpath():
    """Get the Spark classpath by looking for JARs in SPARK_HOME.

    For pre-built distributions, JARs are in $SPARK_HOME/jars/.
    For source builds, JARs are in $SPARK_HOME/assembly/target/scala-*/jars/.

    Returns:
        List of JAR file paths
    """
    import glob

    spark_home = _find_spark_home()

    # First try pre-built distribution path
    jars_dir = os.path.join(spark_home, "jars")
    if os.path.isdir(jars_dir):
        jars = glob.glob(os.path.join(jars_dir, "*.jar"))
        if jars:
            return jars

    # Fall back to source build path (assembly directory)
    assembly_pattern = os.path.join(
        spark_home, "assembly", "target", "scala-*", "jars", "*.jar"
    )
    jars = glob.glob(assembly_pattern)
    if jars:
        return jars

    return []


# =============================================================================
# Java Imports
# =============================================================================

def _do_java_imports(gateway, use_gatun_imports=False):
    """Import standard PySpark classes into the gateway's JVM view.

    Args:
        gateway: The JavaGateway instance
        use_gatun_imports: If True, use more specific imports for Gatun compatibility
    """
    jvm = gateway.jvm

    if use_gatun_imports:
        # Gatun needs more specific imports to avoid ambiguity with wildcard resolution
        java_import(jvm, "org.apache.spark.SparkConf")
        java_import(jvm, "org.apache.spark.api.java.JavaSparkContext")
        java_import(jvm, "org.apache.spark.api.java.JavaRDD")
        java_import(jvm, "org.apache.spark.api.java.JavaPairRDD")
        # api.python must come before ml.python to resolve conflicts
        java_import(jvm, "org.apache.spark.api.python.*")
        java_import(jvm, "org.apache.spark.ml.python.*")
    else:
        # Py4J can use broader wildcard imports
        java_import(jvm, "org.apache.spark.SparkConf")
        java_import(jvm, "org.apache.spark.api.java.*")
        java_import(jvm, "org.apache.spark.api.python.*")
        java_import(jvm, "org.apache.spark.ml.python.*")

    # Common imports for both backends
    java_import(jvm, "org.apache.spark.mllib.api.python.*")
    java_import(jvm, "org.apache.spark.resource.*")
    java_import(jvm, "org.apache.spark.sql.Encoders")
    java_import(jvm, "org.apache.spark.sql.OnSuccessCall")
    java_import(jvm, "org.apache.spark.sql.functions")
    java_import(jvm, "org.apache.spark.sql.classic.*")
    java_import(jvm, "org.apache.spark.sql.api.python.*")
    java_import(jvm, "org.apache.spark.sql.hive.*")
    java_import(jvm, "scala.Tuple2")


# =============================================================================
# Gateway Launchers
# =============================================================================

def _launch_gateway_gatun(conf=None, popen_kwargs=None):
    """Launch Gatun gateway (alternative to Py4J).

    This uses Gatun's shared memory communication instead of Py4J's TCP sockets.
    Spark JARs are added to the classpath so Spark classes are available.

    Parameters
    ----------
    conf : :py:class:`pyspark.SparkConf`
        Spark configuration (currently ignored for Gatun)
    popen_kwargs : dict
        Not used for Gatun

    Returns
    -------
    JavaGateway
        Gatun-compatible gateway
    """
    from gatun import launch_gateway as gatun_launch

    # Get configuration from environment
    memory = os.environ.get("GATUN_MEMORY", "256MB")
    socket_path = os.environ.get("GATUN_SOCKET_PATH")
    spark_classpath = _get_spark_classpath()

    # Launch Gatun server with Spark JARs on classpath
    session = gatun_launch(
        memory=memory,
        socket_path=socket_path,
        classpath=spark_classpath,
    )

    # Create Gatun-compatible gateway
    gateway = JavaGateway(
        socket_path=session.socket_path,
        start_server=False,  # Server already started
    )

    # Store session reference so it doesn't get GC'd
    gateway._gatun_session = session
    gateway.proc = None  # No subprocess to expose (Gatun manages internally)

    # Import PySpark classes
    _do_java_imports(gateway, use_gatun_imports=True)

    # Set spark.master and spark.app.name from env vars (normally done by spark-submit)
    master = os.environ.get("MASTER")
    if master:
        gateway.jvm.java.lang.System.setProperty("spark.master", master)

    app_name = os.environ.get("SPARK_APP_NAME", "PySpark")
    gateway.jvm.java.lang.System.setProperty("spark.app.name", app_name)

    return gateway


def _launch_gateway_py4j(conf=None, popen_kwargs=None):
    """Launch Py4J gateway.

    Parameters
    ----------
    conf : :py:class:`pyspark.SparkConf`
        Spark configuration passed to spark-submit
    popen_kwargs : dict
        Dictionary of kwargs to pass to Popen when spawning the Py4J JVM

    Returns
    -------
    ClientServer or JavaGateway
    """
    if "PYSPARK_GATEWAY_PORT" in os.environ:
        # Connect to existing gateway
        gateway_port = int(os.environ["PYSPARK_GATEWAY_PORT"])
        gateway_secret = os.environ["PYSPARK_GATEWAY_SECRET"]
        proc = None
    else:
        # Launch new gateway process
        gateway_port, gateway_secret, proc = _start_py4j_process(conf, popen_kwargs)

    # Connect to the gateway
    gateway = _connect_to_py4j(gateway_port, gateway_secret)
    gateway.proc = proc

    # Import PySpark classes
    _do_java_imports(gateway, use_gatun_imports=False)

    return gateway


def _start_py4j_process(conf, popen_kwargs):
    """Start the Py4J gateway JVM process.

    Returns:
        Tuple of (gateway_port, gateway_secret, proc)
    """
    spark_home = _find_spark_home()
    on_windows = platform.system() == "Windows"

    # Build spark-submit command
    script = "./bin/spark-submit.cmd" if on_windows else "./bin/spark-submit"
    command = [os.path.join(spark_home, script)]

    if conf:
        for k, v in conf.getAll():
            command += ["--conf", f"{k}={v}"]

    submit_args = os.environ.get("PYSPARK_SUBMIT_ARGS", "pyspark-shell")
    if os.environ.get("SPARK_TESTING"):
        submit_args = " ".join(["--conf spark.ui.enabled=false", submit_args])
    command = command + shlex.split(submit_args)

    # Create temp directory for connection info
    conn_info_dir = tempfile.mkdtemp()
    try:
        fd, conn_info_file = tempfile.mkstemp(dir=conn_info_dir)
        os.close(fd)
        os.unlink(conn_info_file)

        # Set up environment
        env = dict(os.environ)
        env["SPARK_CONNECT_MODE"] = "0"
        env["_PYSPARK_DRIVER_CONN_INFO_PATH"] = conn_info_file

        # Configure popen kwargs
        popen_kwargs = {} if popen_kwargs is None else popen_kwargs
        popen_kwargs["stdin"] = PIPE
        popen_kwargs["env"] = env

        if not on_windows:
            # Don't send SIGINT to the Java gateway
            def preexec_func():
                signal.signal(signal.SIGINT, signal.SIG_IGN)
            popen_kwargs["preexec_fn"] = preexec_func

        # Launch the process
        proc = Popen(command, **popen_kwargs)

        # Wait for connection info file
        while not proc.poll() and not os.path.isfile(conn_info_file):
            time.sleep(0.1)

        if not os.path.isfile(conn_info_file):
            raise PySparkRuntimeError(
                errorClass="JAVA_GATEWAY_EXITED",
                messageParameters={},
            )

        # Read connection info
        with open(conn_info_file, "rb") as info:
            gateway_port = read_int(info)
            gateway_secret = UTF8Deserializer().loads(info)

    finally:
        shutil.rmtree(conn_info_dir)

    # Register cleanup on Windows
    if on_windows:
        def kill_child():
            Popen(["cmd", "/c", "taskkill", "/f", "/t", "/pid", str(proc.pid)])
        atexit.register(kill_child)

    return gateway_port, gateway_secret, proc


def _connect_to_py4j(gateway_port, gateway_secret):
    """Connect to the Py4J gateway.

    Returns:
        ClientServer or JavaGateway depending on PYSPARK_PIN_THREAD setting
    """
    if os.environ.get("PYSPARK_PIN_THREAD", "true").lower() == "true":
        return ClientServer(
            java_parameters=JavaParameters(
                port=gateway_port, auth_token=gateway_secret, auto_convert=True
            ),
            python_parameters=PythonParameters(port=0, eager_load=False),
        )
    else:
        return JavaGateway(
            gateway_parameters=GatewayParameters(
                port=gateway_port, auth_token=gateway_secret, auto_convert=True
            )
        )


# =============================================================================
# Public API
# =============================================================================

def launch_gateway(conf=None, popen_kwargs=None):
    """Launch JVM gateway.

    Parameters
    ----------
    conf : :py:class:`pyspark.SparkConf`
        Spark configuration passed to spark-submit
    popen_kwargs : dict
        Dictionary of kwargs to pass to Popen when spawning the Py4J JVM.
        This is a developer feature intended for use in customizing how
        PySpark interacts with the Py4J JVM (e.g., capturing stdout/stderr).

    Returns
    -------
    ClientServer or JavaGateway
    """
    if _USING_GATUN:
        return _launch_gateway_gatun(conf, popen_kwargs)
    return _launch_gateway_py4j(conf, popen_kwargs)


def ensure_callback_server_started(gw):
    """Start callback server if not already started.

    The callback server is needed if the Java driver process needs to
    callback into the Python driver process to execute Python code.
    """
    # getattr will fallback to JVM, so we cannot test by hasattr()
    if "_callback_server" not in gw.__dict__ or gw._callback_server is None:
        gw.callback_server_parameters.eager_load = True
        gw.callback_server_parameters.daemonize = True
        gw.callback_server_parameters.daemonize_connections = True
        gw.callback_server_parameters.port = 0
        gw.start_callback_server(gw.callback_server_parameters)

        cbport = gw._callback_server.server_socket.getsockname()[1]
        gw._callback_server.port = cbport
        gw._python_proxy_port = gw._callback_server.port

        # Get the GatewayServer object in JVM by ID
        jgws = JavaObject("GATEWAY_SERVER", gw._gateway_client)
        # Update the port of CallbackClient with real port
        jgws.resetCallbackClient(
            jgws.getCallbackClient().getAddress(), gw._python_proxy_port
        )
