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

from typing import Any, Callable, TYPE_CHECKING

if TYPE_CHECKING:
    from pyspark.mllib._typing import C, JavaObjectOrPickleDump

import py4j.protocol

import pyspark.core.context
from pyspark import RDD, SparkContext
from pyspark.jvm_bridge import (
    JavaObjectRef,
    is_java_array,
    is_java_exception,
    is_java_list,
    is_java_object,
)
from pyspark.serializers import CPickleSerializer, AutoBatchedSerializer
from pyspark.sql import DataFrame, SparkSession

# Hack for support float('inf') in Py4j
_old_smart_decode = py4j.protocol.smart_decode

_float_str_mapping = {
    "nan": "NaN",
    "inf": "Infinity",
    "-inf": "-Infinity",
}


def _new_smart_decode(obj: Any) -> str:
    if isinstance(obj, float):
        s = str(obj)
        return _float_str_mapping.get(s, s)
    return _old_smart_decode(obj)


py4j.protocol.smart_decode = _new_smart_decode


_picklable_classes = [
    "LinkedList",
    "SparseVector",
    "DenseVector",
    "DenseMatrix",
    "Rating",
    "LabeledPoint",
]


# this will call the MLlib version of pythonToJava()
def _to_java_object_rdd(rdd: RDD) -> JavaObjectRef:
    """Return a JavaRDD of Object by unpickling

    It will convert each Python object into Java object by Pickle, whenever the
    RDD is serialized in batch or not.
    """
    from pyspark.jvm_bridge import get_bridge

    rdd = rdd._reserialize(AutoBatchedSerializer(CPickleSerializer()))
    return get_bridge().call_static(
        "org.apache.spark.mllib.api.python.SerDe", "pythonToJava", rdd._jrdd, True
    )


def _py2java(sc: SparkContext, obj: Any) -> JavaObjectRef:
    """Convert Python object into Java"""
    from pyspark.jvm_bridge import get_bridge

    if isinstance(obj, RDD):
        obj = _to_java_object_rdd(obj)
    elif isinstance(obj, DataFrame):
        obj = obj._jdf
    elif isinstance(obj, SparkContext):
        obj = obj._jsc
    elif isinstance(obj, list):
        obj = [_py2java(sc, x) for x in obj]
    elif is_java_object(obj):
        pass
    elif isinstance(obj, (int, float, bool, bytes, str)):
        pass
    else:
        data = bytearray(CPickleSerializer().dumps(obj))
        obj = get_bridge().call_static(
            "org.apache.spark.mllib.api.python.SerDe", "loads", data
        )
    return obj


def _java2py(sc: SparkContext, r: "JavaObjectOrPickleDump", encoding: str = "bytes") -> Any:
    from pyspark.jvm_bridge import get_bridge

    if is_java_object(r):
        clsName = r.getClass().getSimpleName()
        # convert RDD into JavaRDD
        if clsName != "JavaRDD" and clsName.endswith("RDD"):
            r = r.toJavaRDD()
            clsName = "JavaRDD"

        bridge = get_bridge()

        if clsName == "JavaRDD":
            jrdd = bridge.call_static(
                "org.apache.spark.mllib.api.python.SerDe", "javaToPython", r
            )
            return RDD(jrdd, sc)

        if clsName == "Dataset":
            return DataFrame(r, SparkSession._getActiveSessionOrCreate())

        if clsName in _picklable_classes:
            r = bridge.call_static("org.apache.spark.mllib.api.python.SerDe", "dumps", r)
        elif is_java_array(r) or is_java_list(r):
            try:
                r = bridge.call_static("org.apache.spark.mllib.api.python.SerDe", "dumps", r)
            except Exception as e:
                if is_java_exception(e):
                    pass  # not pickable
                else:
                    raise

    if isinstance(r, (bytearray, bytes)):
        r = CPickleSerializer().loads(bytes(r), encoding=encoding)
    return r


def callJavaFunc(
    sc: pyspark.core.context.SparkContext, func: Callable[..., "JavaObjectOrPickleDump"], *args: Any
) -> Any:
    """Call Java Function"""
    java_args = [_py2java(sc, a) for a in args]
    return _java2py(sc, func(*java_args))


def callMLlibFunc(name: str, *args: Any) -> Any:
    """Call API in PythonMLLibAPI"""
    from pyspark.jvm_bridge import get_bridge

    sc = SparkContext.getOrCreate()
    bridge = get_bridge()
    python_mllib_api = bridge.new("org.apache.spark.mllib.api.python.PythonMLLibAPI")
    api = getattr(python_mllib_api, name)
    return callJavaFunc(sc, api, *args)


class JavaModelWrapper:
    """
    Wrapper for the model in JVM
    """

    def __init__(self, java_model: JavaObjectRef):
        self._sc = SparkContext.getOrCreate()
        self._java_model = java_model

    def __del__(self) -> None:
        from pyspark.jvm_bridge import get_bridge

        get_bridge().detach(self._java_model)

    def call(self, name: str, *a: Any) -> Any:
        """Call method of java_model"""
        from pyspark.jvm_bridge import get_bridge

        bridge = get_bridge()
        java_args = [_py2java(self._sc, arg) for arg in a]
        result = bridge.call(self._java_model, name, *java_args)
        return _java2py(self._sc, result)


def inherit_doc(cls: "C") -> "C":
    """
    A decorator that makes a class inherit documentation from its parents.
    """
    for name, func in vars(cls).items():
        # only inherit docstring for public functions
        if name.startswith("_"):
            continue
        if not func.__doc__:
            for parent in cls.__bases__:
                parent_func = getattr(parent, name, None)
                if parent_func and getattr(parent_func, "__doc__", None):
                    func.__doc__ = parent_func.__doc__
                    break
    return cls
