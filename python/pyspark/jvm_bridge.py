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

This module provides a clean abstraction layer between PySpark and the JVM backend.
The BridgeAdapter interface defines the contract that any Python-JVM bridge must implement.

Usage:
    from pyspark.jvm_bridge import get_bridge

    bridge = get_bridge()  # Returns the initialized BridgeAdapter
    arr = bridge.new("java.util.ArrayList")
    bridge.call(arr, "add", "hello")
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, Iterator, List, Protocol, Tuple, TYPE_CHECKING, Union, runtime_checkable

if TYPE_CHECKING:
    from py4j.java_gateway import JavaGateway


# =============================================================================
# Protocol Types for Type Annotations
# =============================================================================


@runtime_checkable
class JavaObjectRef(Protocol):
    """Protocol for JVM object references.

    Any object that represents a JVM reference should satisfy this protocol.
    Both Py4J's JavaObject and Gatun's JavaObject work with this.

    Use this type instead of importing py4j.java_gateway.JavaObject directly.
    """

    def __repr__(self) -> str:
        ...


@runtime_checkable
class JavaArrayRef(Protocol):
    """Protocol for JVM array references.

    Supports indexing and length operations.
    """

    def __getitem__(self, index: int) -> Any:
        ...

    def __setitem__(self, index: int, value: Any) -> None:
        ...

    def __len__(self) -> int:
        ...


@runtime_checkable
class JavaMapRef(Protocol):
    """Protocol for JVM Map references.

    Supports dict-like operations.
    """

    def __iter__(self) -> Iterator[Any]:
        ...

    def __getitem__(self, key: Any) -> Any:
        ...

    def keys(self) -> Any:
        ...

    def values(self) -> Any:
        ...

    def items(self) -> Any:
        ...


@runtime_checkable
class JavaListRef(Protocol):
    """Protocol for JVM List references.

    Supports list-like operations.
    """

    def __iter__(self) -> Iterator[Any]:
        ...

    def __getitem__(self, index: int) -> Any:
        ...

    def __len__(self) -> int:
        ...


# =============================================================================
# Exception Types
# =============================================================================


class JavaError(Exception):
    """Base exception for errors originating from the JVM.

    This is a bridge-agnostic exception that wraps Java exceptions.
    Use this instead of importing py4j.protocol.Py4JJavaError directly.
    """

    def __init__(
        self,
        message: str,
        java_exception: Any = None,
        java_class: str = "",
        stack_trace: str = "",
    ):
        super().__init__(message)
        self.java_exception = java_exception
        self.java_class = java_class
        self.stack_trace = stack_trace

    def getMessage(self) -> str:
        """Get the Java exception message."""
        if self.java_exception is not None and hasattr(self.java_exception, "getMessage"):
            return self.java_exception.getMessage() or ""
        return str(self)

    def getCause(self) -> Any:
        """Get the cause of this exception."""
        if self.java_exception is not None and hasattr(self.java_exception, "getCause"):
            return self.java_exception.getCause()
        return None

    def getStackTrace(self) -> Any:
        """Get the Java stack trace."""
        if self.java_exception is not None and hasattr(self.java_exception, "getStackTrace"):
            return self.java_exception.getStackTrace()
        return None


class JavaConnectionError(Exception):
    """Exception for bridge connection errors.

    Raised when the Python-JVM bridge connection fails.
    """

    pass


# =============================================================================
# Type Checking Utilities
# =============================================================================


def is_java_object(obj: Any) -> bool:
    """Check if an object is a JVM object reference.

    Works with both Py4J and Gatun object references.
    """
    # Check for py4j JavaObject
    try:
        from py4j.java_gateway import JavaObject

        if isinstance(obj, JavaObject):
            return True
    except ImportError:
        pass

    # For non-py4j backends, check for specific attributes that indicate a Java object
    # The JavaObjectRef protocol is too loose (only checks __repr__) so we need
    # additional checks for Java-specific attributes
    if hasattr(obj, "_get_object_id") or hasattr(obj, "getClass"):
        return True

    return False


def is_java_array(obj: Any) -> bool:
    """Check if an object is a JVM array reference."""
    try:
        from py4j.java_collections import JavaArray

        if isinstance(obj, JavaArray):
            return True
    except ImportError:
        pass

    # Don't rely on protocol check alone since many Python objects implement
    # the same interface. Check for Java-specific attribute.
    if hasattr(obj, "_get_object_id"):
        return isinstance(obj, JavaArrayRef)
    return False


def is_java_list(obj: Any) -> bool:
    """Check if an object is a JVM List reference."""
    try:
        from py4j.java_collections import JavaList

        if isinstance(obj, JavaList):
            return True
    except ImportError:
        pass

    # Don't rely on protocol check alone since many Python objects implement
    # the same interface. Check for Java-specific attribute.
    if hasattr(obj, "_get_object_id"):
        return isinstance(obj, JavaListRef)
    return False


def is_java_map(obj: Any) -> bool:
    """Check if an object is a JVM Map reference."""
    try:
        from py4j.java_collections import JavaMap

        if isinstance(obj, JavaMap):
            return True
    except ImportError:
        pass

    # Don't rely on protocol check alone since many Python objects implement
    # the same interface. Check for Java-specific attribute.
    if hasattr(obj, "_get_object_id"):
        return isinstance(obj, JavaMapRef)
    return False


def convert_java_map_to_dict(java_map: Any) -> Dict[Any, Any]:
    """Convert a Java Map to a Python dict."""
    bridge = get_bridge()
    result = {}
    entry_set = bridge.call(java_map, "entrySet")
    iterator = bridge.call(entry_set, "iterator")
    while bridge.call(iterator, "hasNext"):
        entry = bridge.call(iterator, "next")
        key = bridge.call(entry, "getKey")
        value = bridge.call(entry, "getValue")
        result[key] = value
    return result


def convert_java_list_to_list(java_list: Any) -> List[Any]:
    """Convert a Java List to a Python list."""
    bridge = get_bridge()
    size = bridge.call(java_list, "size")
    return [bridge.call(java_list, "get", i) for i in range(size)]


def convert_java_array_to_list(java_array: Any) -> List[Any]:
    """Convert a Java array to a Python list."""
    return list(java_array)


def is_java_exception(exc: BaseException) -> bool:
    """Check if an exception is a Java exception from the bridge.

    Works with both Py4J's Py4JJavaError and other bridge exceptions.
    """
    try:
        from py4j.protocol import Py4JJavaError

        if isinstance(exc, Py4JJavaError):
            return True
    except ImportError:
        pass

    return isinstance(exc, JavaError)


def get_java_exception(exc: BaseException) -> Any:
    """Get the underlying Java exception from a bridge exception.

    Returns the Java exception object, or None if not a Java exception.
    """
    try:
        from py4j.protocol import Py4JJavaError

        if isinstance(exc, Py4JJavaError):
            return exc.java_exception
    except ImportError:
        pass

    if isinstance(exc, JavaError):
        return exc.java_exception

    return None


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
    def new(self, class_name: str, *args: Any) -> Any:
        """Create a new JVM object.

        Args:
            class_name: Fully qualified class name (e.g., "java.util.ArrayList")
            *args: Constructor arguments (Python types auto-converted)

        Returns:
            Reference to the created object
        """
        ...

    @abstractmethod
    def close(self) -> None:
        """Close the bridge and release all resources."""
        ...

    @abstractmethod
    def detach(self, ref: Any) -> None:
        """Prevent automatic cleanup of this object reference.

        Used when passing objects to long-lived Java structures that will
        manage the object's lifecycle.
        """
        ...

    # === Method Calls ===

    @abstractmethod
    def call(self, ref: Any, method: str, *args: Any) -> Any:
        """Call an instance method on a JVM object.

        Args:
            ref: Object reference
            method: Method name
            *args: Method arguments (auto-converted)

        Returns:
            Method result (object ref for objects, Python types for primitives)
        """
        ...

    @abstractmethod
    def call_static(self, class_name: str, method: str, *args: Any) -> Any:
        """Call a static method on a JVM class.

        Args:
            class_name: Fully qualified class name
            method: Static method name
            *args: Method arguments

        Returns:
            Method result
        """
        ...

    # === Field Access ===

    @abstractmethod
    def get_field(self, ref: Any, name: str) -> Any:
        """Get an instance field value."""
        ...

    @abstractmethod
    def set_field(self, ref: Any, name: str, value: Any) -> None:
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
    def is_instance_of(self, ref: Any, class_name: str) -> bool:
        """Check if object is instance of class (supports interfaces)."""
        ...

    # === Arrays ===

    @abstractmethod
    def new_array(self, element_class: str, length: int) -> Any:
        """Create a new JVM array.

        Args:
            element_class: Element type. For primitives use lowercase
                          ("int", "long", "double", etc.). For objects
                          use fully qualified name ("java.lang.String").
            length: Array length

        Returns:
            Reference to the array
        """
        ...

    @abstractmethod
    def array_get(self, array_ref: Any, index: int) -> Any:
        """Get element at index from JVM array."""
        ...

    @abstractmethod
    def array_set(self, array_ref: Any, index: int, value: Any) -> None:
        """Set element at index in JVM array."""
        ...

    @abstractmethod
    def array_length(self, array_ref: Any) -> int:
        """Get length of JVM array."""
        ...

    # === JVM View ===

    @property
    @abstractmethod
    def jvm(self) -> Any:
        """Get JVM view for navigating classes.

        Allows: bridge.jvm.java.util.ArrayList()
        Instead of: bridge.new("java.util.ArrayList")
        """
        ...

    @abstractmethod
    def java_import(self, package: str) -> None:
        """Import package for shorter class names.

        Args:
            package: Package path with optional wildcard (e.g., "java.util.*")

        After calling java_import("java.util.*"), you can use:
            bridge.jvm.ArrayList() instead of bridge.jvm.java.util.ArrayList()
        """
        ...

    # === Type Conversion ===

    @property
    @abstractmethod
    def gateway_client(self) -> Any:
        """Get the gateway client for type converters.

        This is used by py4j's converter protocol where converters receive
        the gateway_client to create JavaClass instances.
        """
        ...

    @abstractmethod
    def java_class(self, class_name: str) -> Any:
        """Get a JavaClass reference for type conversion.

        Args:
            class_name: Fully qualified class name

        Returns:
            A callable class reference that can be used to call static methods
            or create instances.

        This is used by type converters that need to call static methods on
        Java classes (e.g., Date.valueOf, LocalTime.of).
        """
        ...

    @abstractmethod
    def register_input_converter(self, converter: Any, prepend: bool = False) -> None:
        """Register a Python to Java type converter.

        Args:
            converter: A converter object with can_convert(obj) and
                      convert(obj, gateway_client) methods.
            prepend: If True, add to front of converter list (checked first).

        Converters are checked in order when Python values are passed to Java.
        """
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

    # === Internal Helpers ===

    def _navigate_to_class(self, class_name: str) -> Any:
        """Navigate to a JVM class by fully qualified name.

        Handles both regular classes (org.apache.spark.Foo) and nested/Scala
        objects (org.apache.spark.Foo$Bar$) by treating both '.' and '$' as
        separators for getattr navigation.
        """
        import re

        # Split on both '.' and '$', keeping track of separators
        # e.g., "org.apache.spark.Foo$Bar$" -> ["org", "apache", "spark", "Foo", "Bar", ""]
        parts = re.split(r"[.$]", class_name)
        cls = self._gateway.jvm
        for part in parts:
            if part:  # Skip empty parts (e.g., trailing $)
                cls = getattr(cls, part)
        return cls

    # === Object Lifecycle ===

    def new(self, class_name: str, *args: Any) -> Any:
        """Create a new JVM object."""
        cls = self._navigate_to_class(class_name)
        return cls(*args)

    def close(self) -> None:
        """Close the bridge and release all resources."""
        if self._gateway:
            self._gateway.shutdown()
            self._gateway = None

    def detach(self, ref: Any) -> None:
        """Prevent automatic cleanup of this object reference."""
        if hasattr(ref, "_detach"):
            ref._detach()
        elif hasattr(self._gateway, "detach"):
            self._gateway.detach(ref)

    # === Method Calls ===

    def call(self, ref: Any, method: str, *args: Any) -> Any:
        """Call an instance method on a JVM object."""
        method_obj = getattr(ref, method)
        return method_obj(*args)

    def call_static(self, class_name: str, method: str, *args: Any) -> Any:
        """Call a static method on a JVM class."""
        cls = self._navigate_to_class(class_name)
        method_obj = getattr(cls, method)
        return method_obj(*args)

    # === Field Access ===

    def get_field(self, ref: Any, name: str) -> Any:
        """Get an instance field value."""
        return getattr(ref, name)

    def set_field(self, ref: Any, name: str, value: Any) -> None:
        """Set an instance field value."""
        setattr(ref, name, value)

    def get_static_field(self, class_name: str, name: str) -> Any:
        """Get a static field value."""
        cls = self._navigate_to_class(class_name)
        return getattr(cls, name)

    def set_static_field(self, class_name: str, name: str, value: Any) -> None:
        """Set a static field value."""
        cls = self._navigate_to_class(class_name)
        setattr(cls, name, value)

    # === Type Checking ===

    def is_instance_of(self, ref: Any, class_name: str) -> bool:
        """Check if object is instance of class."""
        from py4j.java_gateway import is_instance_of

        return is_instance_of(self._gateway, ref, class_name)

    # === Arrays ===

    def new_array(self, element_class: str, length: int) -> Any:
        """Create a new JVM array."""
        cls = self._navigate_to_class(element_class)
        return self._gateway.new_array(cls, length)

    def array_get(self, array_ref: Any, index: int) -> Any:
        """Get element at index from JVM array."""
        return array_ref[index]

    def array_set(self, array_ref: Any, index: int, value: Any) -> None:
        """Set element at index in JVM array."""
        array_ref[index] = value

    def array_length(self, array_ref: Any) -> int:
        """Get length of JVM array."""
        return len(array_ref)

    # === JVM View ===

    @property
    def jvm(self) -> Any:
        """Get JVM view for navigating classes."""
        return self._gateway.jvm

    def java_import(self, package: str) -> None:
        """Import package for shorter class names."""
        from py4j.java_gateway import java_import

        java_import(self._gateway.jvm, package)

    # === Type Conversion ===

    @property
    def gateway_client(self) -> Any:
        """Get the gateway client for type converters."""
        return self._gateway._gateway_client

    def java_class(self, class_name: str) -> Any:
        """Get a JavaClass reference for type conversion."""
        from py4j.java_gateway import JavaClass

        return JavaClass(class_name, self._gateway._gateway_client)

    def register_input_converter(self, converter: Any, prepend: bool = False) -> None:
        """Register a Python to Java type converter."""
        from py4j.protocol import register_input_converter

        register_input_converter(converter, prepend=prepend)


# =============================================================================
# Bridge Factory
# =============================================================================


_bridge: BridgeAdapter | None = None


def get_bridge() -> BridgeAdapter:
    """Get the singleton BridgeAdapter instance.

    Returns the bridge adapter. The bridge must be initialized first via
    create_bridge_from_gateway() during SparkContext initialization.

    Raises:
        RuntimeError: If the bridge has not been initialized yet.
    """
    global _bridge
    if _bridge is None:
        raise RuntimeError(
            "Bridge not initialized. "
            "Use create_bridge_from_gateway() to initialize during SparkContext setup."
        )
    return _bridge


def get_jvm() -> Any:
    """Get the JVM view for navigating classes.

    Convenience function that returns get_bridge().jvm.

    Returns:
        JVM view for navigating JVM class hierarchy.

    Raises:
        RuntimeError: If the bridge has not been initialized yet.
    """
    return get_bridge().jvm


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
