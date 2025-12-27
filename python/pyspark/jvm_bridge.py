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
JVM Bridge abstraction for PySpark.

This module provides a clean abstraction layer between PySpark and the JVM backend,
allowing both Py4J and Gatun to implement the same contract.

Usage:
    from pyspark.jvm_bridge import get_bridge, BridgeAdapter

    bridge = get_bridge()  # Returns Py4JAdapter or GatunAdapter based on config
    arr = bridge.new("java.util.ArrayList")
    bridge.call(arr, "add", "hello")
"""

from __future__ import annotations

import os
from abc import ABC, abstractmethod
from typing import Any, Protocol, runtime_checkable, TYPE_CHECKING

if TYPE_CHECKING:
    from py4j.java_gateway import JavaGateway


# =============================================================================
# Protocol Definitions
# =============================================================================


@runtime_checkable
class JVMRef(Protocol):
    """Opaque reference to a JVM object."""

    @property
    def object_id(self) -> int:
        """Unique identifier for this object in the JVM."""
        ...


class JVMView(ABC):
    """View into JVM class hierarchy.

    Supports attribute-style navigation:
        jvm.java.util.ArrayList  -> class reference
        jvm.java.util.ArrayList() -> new instance
        jvm.java.lang.Integer.MAX_VALUE -> static field
        jvm.java.lang.Integer.parseInt("42") -> static method call
    """

    @abstractmethod
    def __getattr__(self, name: str) -> "JVMView | JVMRef | Any":
        """Navigate to package, class, or access static member."""
        ...

    @abstractmethod
    def __call__(self, *args: Any) -> JVMRef:
        """Create instance of this class."""
        ...


# =============================================================================
# Exception Hierarchy
# =============================================================================


class JavaException(Exception):
    """Base class for Java exceptions raised in Python."""

    def __init__(self, java_class: str, message: str, stack_trace: str = ""):
        self.java_class = java_class
        self.message = message
        self.stack_trace = stack_trace
        super().__init__(f"{java_class}: {message}")


class JavaSecurityException(JavaException):
    """java.lang.SecurityException"""

    pass


class JavaIllegalArgumentException(JavaException):
    """java.lang.IllegalArgumentException"""

    pass


class JavaNoSuchMethodException(JavaException):
    """java.lang.NoSuchMethodException"""

    pass


class JavaClassNotFoundException(JavaException):
    """java.lang.ClassNotFoundException"""

    pass


class JavaNullPointerException(JavaException):
    """java.lang.NullPointerException"""

    pass


class JavaIndexOutOfBoundsException(JavaException):
    """java.lang.IndexOutOfBoundsException"""

    pass


# Mapping from Java exception class names to Python exception classes
JAVA_EXCEPTION_MAP = {
    "java.lang.SecurityException": JavaSecurityException,
    "java.lang.IllegalArgumentException": JavaIllegalArgumentException,
    "java.lang.NoSuchMethodException": JavaNoSuchMethodException,
    "java.lang.ClassNotFoundException": JavaClassNotFoundException,
    "java.lang.NullPointerException": JavaNullPointerException,
    "java.lang.IndexOutOfBoundsException": JavaIndexOutOfBoundsException,
    "java.lang.ArrayIndexOutOfBoundsException": JavaIndexOutOfBoundsException,
}


# =============================================================================
# BridgeAdapter Abstract Base Class
# =============================================================================


class BridgeAdapter(ABC):
    """Abstract base class defining the bridge contract.

    This is the minimal API that PySpark needs to communicate with a JVM.
    Implementations can be backed by Py4J, Gatun, or any other bridge.
    """

    # === Object Lifecycle ===

    @abstractmethod
    def new(self, class_name: str, *args: Any) -> JVMRef:
        """Create a new JVM object."""
        ...

    @abstractmethod
    def close(self) -> None:
        """Close the bridge and release all resources."""
        ...

    @abstractmethod
    def detach(self, ref: JVMRef) -> None:
        """Prevent automatic cleanup of this object reference."""
        ...

    # === Method Calls ===

    @abstractmethod
    def call(self, ref: JVMRef, method: str, *args: Any) -> Any:
        """Call an instance method on a JVM object."""
        ...

    @abstractmethod
    def call_static(self, class_name: str, method: str, *args: Any) -> Any:
        """Call a static method on a JVM class."""
        ...

    # === Field Access ===

    @abstractmethod
    def get_field(self, ref: JVMRef, name: str) -> Any:
        """Get an instance field value."""
        ...

    @abstractmethod
    def set_field(self, ref: JVMRef, name: str, value: Any) -> None:
        """Set an instance field value."""
        ...

    @abstractmethod
    def get_static_field(self, class_name: str, name: str) -> Any:
        """Get a static field value."""
        ...

    @abstractmethod
    def set_static_field(self, class_name: str, name: str, value: Any) -> None:
        """Set a static field value."""
        ...

    # === Type Checking ===

    @abstractmethod
    def is_instance_of(self, ref: JVMRef, class_name: str) -> bool:
        """Check if object is instance of class."""
        ...

    # === Arrays ===

    @abstractmethod
    def new_array(self, element_class: str, length: int) -> JVMRef:
        """Create a new JVM array."""
        ...

    @abstractmethod
    def array_get(self, array_ref: JVMRef, index: int) -> Any:
        """Get element at index from JVM array."""
        ...

    @abstractmethod
    def array_set(self, array_ref: JVMRef, index: int, value: Any) -> None:
        """Set element at index in JVM array."""
        ...

    @abstractmethod
    def array_length(self, array_ref: JVMRef) -> int:
        """Get length of JVM array."""
        ...

    # === JVM View ===

    @property
    @abstractmethod
    def jvm(self) -> JVMView:
        """Get JVM view for navigating classes."""
        ...

    @abstractmethod
    def java_import(self, package: str) -> None:
        """Import package for shorter class names."""
        ...


# =============================================================================
# Py4J Adapter
# =============================================================================


class Py4JAdapter(BridgeAdapter):
    """BridgeAdapter implementation backed by Py4J."""

    def __init__(self, gateway: "JavaGateway"):
        """Create a new Py4JAdapter.

        Args:
            gateway: An existing Py4J JavaGateway instance
        """
        self._gateway = gateway

    # === Object Lifecycle ===

    def new(self, class_name: str, *args: Any) -> JVMRef:
        """Create a new JVM object."""
        from py4j.protocol import Py4JJavaError

        try:
            # Navigate to the class via JVM view
            parts = class_name.split(".")
            cls = self._gateway.jvm
            for part in parts:
                cls = getattr(cls, part)
            return cls(*args)
        except Py4JJavaError as e:
            raise self._convert_exception(e) from None

    def close(self) -> None:
        """Close the bridge and release all resources."""
        if self._gateway:
            self._gateway.shutdown()
            self._gateway = None

    def detach(self, ref: JVMRef) -> None:
        """Prevent automatic cleanup of this object reference."""
        if hasattr(ref, "_detach"):
            ref._detach()
        elif hasattr(self._gateway, "detach"):
            self._gateway.detach(ref)

    # === Method Calls ===

    def call(self, ref: JVMRef, method: str, *args: Any) -> Any:
        """Call an instance method on a JVM object."""
        from py4j.protocol import Py4JJavaError

        try:
            method_obj = getattr(ref, method)
            return method_obj(*args)
        except Py4JJavaError as e:
            raise self._convert_exception(e) from None

    def call_static(self, class_name: str, method: str, *args: Any) -> Any:
        """Call a static method on a JVM class."""
        from py4j.protocol import Py4JJavaError

        try:
            parts = class_name.split(".")
            cls = self._gateway.jvm
            for part in parts:
                cls = getattr(cls, part)
            method_obj = getattr(cls, method)
            return method_obj(*args)
        except Py4JJavaError as e:
            raise self._convert_exception(e) from None

    # === Field Access ===

    def get_field(self, ref: JVMRef, name: str) -> Any:
        """Get an instance field value."""
        from py4j.protocol import Py4JJavaError

        try:
            return getattr(ref, name)
        except Py4JJavaError as e:
            raise self._convert_exception(e) from None

    def set_field(self, ref: JVMRef, name: str, value: Any) -> None:
        """Set an instance field value."""
        from py4j.protocol import Py4JJavaError

        try:
            setattr(ref, name, value)
        except Py4JJavaError as e:
            raise self._convert_exception(e) from None

    def get_static_field(self, class_name: str, name: str) -> Any:
        """Get a static field value."""
        from py4j.protocol import Py4JJavaError

        try:
            parts = class_name.split(".")
            cls = self._gateway.jvm
            for part in parts:
                cls = getattr(cls, part)
            return getattr(cls, name)
        except Py4JJavaError as e:
            raise self._convert_exception(e) from None

    def set_static_field(self, class_name: str, name: str, value: Any) -> None:
        """Set a static field value."""
        from py4j.protocol import Py4JJavaError

        try:
            parts = class_name.split(".")
            cls = self._gateway.jvm
            for part in parts:
                cls = getattr(cls, part)
            setattr(cls, name, value)
        except Py4JJavaError as e:
            raise self._convert_exception(e) from None

    # === Type Checking ===

    def is_instance_of(self, ref: JVMRef, class_name: str) -> bool:
        """Check if object is instance of class."""
        from py4j.java_gateway import is_instance_of

        return is_instance_of(self._gateway, ref, class_name)

    # === Arrays ===

    def new_array(self, element_class: str, length: int) -> JVMRef:
        """Create a new JVM array."""
        # Navigate to the class
        parts = element_class.split(".")
        cls = self._gateway.jvm
        for part in parts:
            cls = getattr(cls, part)
        return self._gateway.new_array(cls, length)

    def array_get(self, array_ref: JVMRef, index: int) -> Any:
        """Get element at index from JVM array."""
        return array_ref[index]

    def array_set(self, array_ref: JVMRef, index: int, value: Any) -> None:
        """Set element at index in JVM array."""
        array_ref[index] = value

    def array_length(self, array_ref: JVMRef) -> int:
        """Get length of JVM array."""
        return len(array_ref)

    # === JVM View ===

    @property
    def jvm(self) -> JVMView:
        """Get JVM view for navigating classes."""
        return self._gateway.jvm

    def java_import(self, package: str) -> None:
        """Import package for shorter class names."""
        from py4j.java_gateway import java_import

        java_import(self._gateway.jvm, package)

    # === Exception Conversion ===

    def _convert_exception(self, exc) -> JavaException:
        """Convert Py4J exception to bridge JavaException."""
        java_class = ""
        message = str(exc)
        stack_trace = ""

        if hasattr(exc, "java_exception"):
            java_exc = exc.java_exception
            if hasattr(java_exc, "getClass"):
                java_class = java_exc.getClass().getName()
            if hasattr(java_exc, "getMessage"):
                message = java_exc.getMessage() or message
            if hasattr(java_exc, "toString"):
                stack_trace = str(java_exc)

        exc_class = JAVA_EXCEPTION_MAP.get(java_class, JavaException)
        return exc_class(java_class, message, stack_trace)


# =============================================================================
# Gatun Adapter
# =============================================================================


class GatunAdapter(BridgeAdapter):
    """BridgeAdapter implementation backed by Gatun."""

    def __init__(self, memory: str = "256MB", classpath: list[str] | None = None):
        """Create a new GatunAdapter.

        Args:
            memory: Shared memory size (e.g., "64MB", "256MB")
            classpath: Additional JAR files for the classpath
        """
        from gatun import connect as gatun_connect

        self._client = gatun_connect(memory=memory)

    # === Object Lifecycle ===

    def new(self, class_name: str, *args: Any) -> JVMRef:
        """Create a new JVM object."""
        try:
            return self._client.create_object(class_name, *args)
        except Exception as e:
            raise self._convert_exception(e) from None

    def close(self) -> None:
        """Close the bridge and release all resources."""
        if self._client:
            self._client.close()
            self._client = None

    def detach(self, ref: JVMRef) -> None:
        """Prevent automatic cleanup of this object reference."""
        if hasattr(ref, "detach"):
            ref.detach()

    # === Method Calls ===

    def call(self, ref: JVMRef, method: str, *args: Any) -> Any:
        """Call an instance method on a JVM object."""
        try:
            obj_id = ref.object_id if hasattr(ref, "object_id") else ref
            return self._client.invoke_method(obj_id, method, *args)
        except Exception as e:
            raise self._convert_exception(e) from None

    def call_static(self, class_name: str, method: str, *args: Any) -> Any:
        """Call a static method on a JVM class."""
        try:
            return self._client.invoke_static_method(class_name, method, *args)
        except Exception as e:
            raise self._convert_exception(e) from None

    # === Field Access ===

    def get_field(self, ref: JVMRef, name: str) -> Any:
        """Get an instance field value."""
        try:
            obj_id = ref.object_id if hasattr(ref, "object_id") else ref
            return self._client.get_field(obj_id, name)
        except Exception as e:
            raise self._convert_exception(e) from None

    def set_field(self, ref: JVMRef, name: str, value: Any) -> None:
        """Set an instance field value."""
        try:
            obj_id = ref.object_id if hasattr(ref, "object_id") else ref
            self._client.set_field(obj_id, name, value)
        except Exception as e:
            raise self._convert_exception(e) from None

    def get_static_field(self, class_name: str, name: str) -> Any:
        """Get a static field value."""
        try:
            return self._client.get_static_field(class_name, name)
        except Exception as e:
            raise self._convert_exception(e) from None

    def set_static_field(self, class_name: str, name: str, value: Any) -> None:
        """Set a static field value."""
        try:
            self._client.set_static_field(class_name, name, value)
        except Exception as e:
            raise self._convert_exception(e) from None

    # === Type Checking ===

    def is_instance_of(self, ref: JVMRef, class_name: str) -> bool:
        """Check if object is instance of class."""
        try:
            return self._client.is_instance_of(ref, class_name)
        except Exception as e:
            raise self._convert_exception(e) from None

    # === Arrays ===

    _PRIMITIVE_TYPES = {
        "int": ("java.lang.Integer", "TYPE"),
        "long": ("java.lang.Long", "TYPE"),
        "double": ("java.lang.Double", "TYPE"),
        "float": ("java.lang.Float", "TYPE"),
        "boolean": ("java.lang.Boolean", "TYPE"),
        "byte": ("java.lang.Byte", "TYPE"),
        "short": ("java.lang.Short", "TYPE"),
        "char": ("java.lang.Character", "TYPE"),
    }

    def new_array(self, element_class: str, length: int) -> JVMRef:
        """Create a new JVM array."""
        try:
            if element_class in self._PRIMITIVE_TYPES:
                wrapper_class, field = self._PRIMITIVE_TYPES[element_class]
                class_obj = self._client.get_static_field(wrapper_class, field)
            else:
                class_obj = self._client.invoke_static_method(
                    "java.lang.Class", "forName", element_class
                )
            return self._client.invoke_static_method(
                "java.lang.reflect.Array", "newInstance", class_obj, length
            )
        except Exception as e:
            raise self._convert_exception(e) from None

    def array_get(self, array_ref: JVMRef, index: int) -> Any:
        """Get element at index from JVM array."""
        try:
            return self._client.invoke_static_method(
                "java.lang.reflect.Array", "get", array_ref, index
            )
        except Exception as e:
            raise self._convert_exception(e) from None

    def array_set(self, array_ref: JVMRef, index: int, value: Any) -> None:
        """Set element at index in JVM array."""
        try:
            self._client.invoke_static_method(
                "java.lang.reflect.Array", "set", array_ref, index, value
            )
        except Exception as e:
            raise self._convert_exception(e) from None

    def array_length(self, array_ref: JVMRef) -> int:
        """Get length of JVM array."""
        try:
            return self._client.invoke_static_method(
                "java.lang.reflect.Array", "getLength", array_ref
            )
        except Exception as e:
            raise self._convert_exception(e) from None

    # === JVM View ===

    @property
    def jvm(self) -> JVMView:
        """Get JVM view for navigating classes."""
        return self._client.jvm

    def java_import(self, package: str) -> None:
        """Import package for shorter class names."""
        from gatun import java_import as gatun_java_import

        gatun_java_import(self._client.jvm, package)

    # === Exception Conversion ===

    def _convert_exception(self, exc) -> JavaException:
        """Convert Gatun exception to bridge JavaException."""
        java_class = getattr(exc, "java_class", type(exc).__name__)
        message = getattr(exc, "message", str(exc))
        stack_trace = getattr(exc, "stack_trace", "")

        exc_class = JAVA_EXCEPTION_MAP.get(java_class, JavaException)
        return exc_class(java_class, message, stack_trace)


# =============================================================================
# Bridge Factory
# =============================================================================


_bridge: BridgeAdapter | None = None


def get_bridge() -> BridgeAdapter:
    """Get the singleton BridgeAdapter instance.

    Returns the appropriate adapter based on configuration:
    - If PYSPARK_USE_GATUN=true, returns GatunAdapter
    - Otherwise, returns Py4JAdapter (requires existing gateway)

    This function is intended for use during SparkContext initialization.
    """
    global _bridge
    if _bridge is not None:
        return _bridge

    use_gatun = os.environ.get("PYSPARK_USE_GATUN", "").lower() in ("true", "1", "yes")

    if use_gatun:
        memory = os.environ.get("GATUN_MEMORY", "256MB")
        _bridge = GatunAdapter(memory=memory)
    else:
        raise RuntimeError(
            "Py4J bridge requires an existing gateway. "
            "Use create_bridge_from_gateway() instead."
        )

    return _bridge


def create_bridge_from_gateway(gateway: "JavaGateway") -> BridgeAdapter:
    """Create a BridgeAdapter from an existing Py4J gateway.

    Args:
        gateway: An existing Py4J JavaGateway instance

    Returns:
        A Py4JAdapter wrapping the gateway
    """
    global _bridge
    _bridge = Py4JAdapter(gateway)
    return _bridge


def close_bridge() -> None:
    """Close the singleton bridge and release resources."""
    global _bridge
    if _bridge is not None:
        _bridge.close()
        _bridge = None
