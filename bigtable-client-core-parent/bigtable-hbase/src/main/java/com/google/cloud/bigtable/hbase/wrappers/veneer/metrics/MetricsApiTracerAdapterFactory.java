/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.hbase.wrappers.veneer.metrics;

import com.google.api.gax.tracing.ApiTracer;
import com.google.api.gax.tracing.ApiTracerFactory;
import com.google.api.gax.tracing.SpanName;
import com.google.bigtable.v2.BigtableGrpc;
import com.google.cloud.bigtable.metrics.RpcMetrics;
import java.util.HashMap;

public class MetricsApiTracerAdapterFactory implements ApiTracerFactory {
  private final RpcMetrics readRowsMetrics =
      RpcMetrics.createRpcMetrics(BigtableGrpc.getReadRowsMethod().getFullMethodName());
  private final RpcMetrics mutateRowMetrics =
      RpcMetrics.createRpcMetrics(BigtableGrpc.getMutateRowMethod().getFullMethodName());
  private HashMap<String, RpcMetrics> otherMetrics = new HashMap<>();

  @Override
  public ApiTracer newTracer(ApiTracer parent, SpanName spanName, OperationType operationType) {
    RpcMetrics rpcMetrics = getRpcMetrics(spanName);
    return new MetricsApiTracerAdapter(rpcMetrics);
  }

  private RpcMetrics getRpcMetrics(SpanName spanName) {
    String key = spanName.getMethodName();

    switch (key) {
      case "ReadRows":
        return readRowsMetrics;
      case "MutateRow":
        return mutateRowMetrics;
      default:
        RpcMetrics metrics = otherMetrics.get(key);
        if (metrics != null) {
          return metrics;
        }
        synchronized (this) {
          metrics = otherMetrics.get(key);
          if (metrics != null) {
            return metrics;
          }
          metrics = RpcMetrics.createRpcMetrics(key);
          otherMetrics.put(key, metrics);
          return metrics;
        }
    }
  }
}
