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
from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from py4j.java_gateway import JavaGateway


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
