/*
 * Copyright 2015 Google Inc. All Rights Reserved.
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
package com.google.cloud.bigtable.grpc.async;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.bigtable.v2.BigtableGrpc;
import com.google.bigtable.v2.ReadRowsRequest;
import com.google.bigtable.v2.ReadRowsResponse;
import com.google.cloud.bigtable.config.RetryOptions;
import com.google.cloud.bigtable.grpc.scanner.BigtableRetriesExhaustedException;
import com.google.cloud.bigtable.metrics.RpcMetrics;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientCall.Listener;
import io.grpc.DeadlineUtil;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Test for {@link RetryingUnaryOperation} and {@link AbstractRetryingOperation} functionality. */
@RunWith(JUnit4.class)
@SuppressWarnings({"unchecked", "rawtypes"})
public class TestRetryingUnaryOperation {
  @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();

  private static final RetryOptions RETRY_OPTIONS = RetryOptions.getDefaultOptions();

  private static final RpcMetrics metrics =
      BigtableAsyncUtilities.Default.createRpcMetrics(BigtableGrpc.getReadRowsMethod());

  @Mock private BigtableAsyncRpc<ReadRowsRequest, ReadRowsResponse> readAsync;

  private OperationClock clock;

  @Mock private ScheduledExecutorService executorService;

  @Before
  public void setup() {
    when(readAsync.getRpcMetrics()).thenReturn(metrics);
    when(readAsync.getMethodDescriptor()).thenReturn(BigtableGrpc.getReadRowsMethod());
    when(readAsync.isRetryable(any(ReadRowsRequest.class))).thenReturn(true);

    clock = new OperationClock();
    clock.initializeMockSchedule(executorService, null);
  }

  @Test
  public void testOK() throws Exception {
    final ReadRowsResponse result = ReadRowsResponse.getDefaultInstance();
    Answer<Void> answer =
        new Answer<Void>() {
          @Override
          public Void answer(InvocationOnMock invocation) {
            Listener listener = invocation.getArgument(1, ClientCall.Listener.class);
            listener.onMessage(result);
            listener.onClose(Status.OK, null);
            return null;
          }
        };
    doAnswer(answer)
        .when(readAsync)
        .start(
            (ReadRowsRequest) any(),
            (ClientCall.Listener) any(),
            (Metadata) any(),
            (ClientCall) any());
    ListenableFuture future = createOperation(CallOptions.DEFAULT).getAsyncResult();
    Assert.assertEquals(result, future.get(1, TimeUnit.SECONDS));
    verify(readAsync, times(1))
        .start(
            (ReadRowsRequest) any(),
            (ClientCall.Listener) any(),
            (Metadata) any(),
            (ClientCall) any());
  }

  @Test
  public void testRecoveredFailure() throws Exception {
    final ReadRowsResponse result = ReadRowsResponse.getDefaultInstance();
    final Status errorStatus = Status.UNAVAILABLE;
    final AtomicInteger counter = new AtomicInteger(0);
    Answer<Void> answer =
        new Answer<Void>() {
          @Override
          public Void answer(InvocationOnMock invocation) throws Throwable {
            Listener listener = invocation.getArgument(1);
            if (counter.incrementAndGet() < 5) {
              listener.onClose(errorStatus, null);
            } else {
              listener.onMessage(result);
              listener.onClose(Status.OK, null);
            }
            return null;
          }
        };
    doAnswer(answer)
        .when(readAsync)
        .start(
            Mockito.<ReadRowsRequest>any(),
            Mockito.<ClientCall.Listener>any(),
            Mockito.<Metadata>any(),
            Mockito.<ClientCall>any());
    ListenableFuture future = createOperation(CallOptions.DEFAULT).getAsyncResult();

    Assert.assertEquals(result, future.get(1, TimeUnit.SECONDS));
    Assert.assertEquals(5, counter.get());
  }

  @Test
  public void testCompleteFailure_DEFAULT() throws Exception {
    testTimeout(RETRY_OPTIONS.getMaxElapsedBackoffMillis(), CallOptions.DEFAULT);
  }

  @Test
  public void testCompleteFailure_Deadline() throws Exception {
    CallOptions options = DeadlineUtil.optionsWithDeadline(1, TimeUnit.SECONDS, clock);
    testTimeout(TimeUnit.SECONDS.toMillis(1), options);
  }

  private void testTimeout(long expectedTimeoutMs, CallOptions options)
      throws InterruptedException, java.util.concurrent.TimeoutException {
    testTimeout(
        expectedTimeoutMs,
        options,
        Status.UNAVAILABLE,
        /* Do maximum possible attempts within deadline */ 0);
  }

  private void testDeadlineExceeded(long expectedTimeoutMs, CallOptions options)
      throws InterruptedException, java.util.concurrent.TimeoutException {
    // When the deadline is exceeded waiting for response, there is no time for a retry, hence only
    // 1 attempt.
    testTimeout(expectedTimeoutMs, options, Status.DEADLINE_EXCEEDED, 1);
  }

  /**
   * Helper method to test timeout scenarios
   *
   * @param expectedTimeoutMs expected timeout in millis
   * @param options call options for RPC calls.
   * @param expectedError the error returned from the mock API call.
   * @param expectedNumberOfAttempts Expected number of attempted RPCs, it includes the original
   *     call and all the retries. Passing 0 cancels the validation on the number of attempts.
   * @throws InterruptedException
   * @throws java.util.concurrent.TimeoutException
   */
  private void testTimeout(
      final long expectedTimeoutMs,
      CallOptions options,
      final Status expectedError,
      final int expectedNumberOfAttempts)
      throws InterruptedException, java.util.concurrent.TimeoutException {
    final AtomicInteger counter = new AtomicInteger(0);
    Answer<Void> answer =
        new Answer<Void>() {
          @Override
          public Void answer(InvocationOnMock invocation) {
            if (expectedError == Status.DEADLINE_EXCEEDED) {
              // Simulate the client waiting for the response.
              clock.incrementSleepTime(expectedTimeoutMs, TimeUnit.MILLISECONDS);
            }
            counter.incrementAndGet();
            invocation.<Listener>getArgument(1).onClose(expectedError, null);
            return null;
          }
        };
    doAnswer(answer)
        .when(readAsync)
        .start((ReadRowsRequest) any(), (Listener) any(), (Metadata) any(), (ClientCall) any());
    try {
      createOperation(options).getAsyncResult().get(1, TimeUnit.SECONDS);
      Assert.fail();
    } catch (ExecutionException e) {
      Assert.assertEquals(BigtableRetriesExhaustedException.class, e.getCause().getClass());
      Assert.assertEquals(expectedError.getCode(), Status.fromThrowable(e).getCode());
    }

    if (expectedNumberOfAttempts > 0) {
      Assert.assertEquals(expectedNumberOfAttempts, counter.get());
    }
    clock.assertTimeWithinExpectations(TimeUnit.MILLISECONDS.toNanos(expectedTimeoutMs));
  }

  private RetryingUnaryOperation createOperation(CallOptions options) {
    return new RetryingUnaryOperation<>(
        RETRY_OPTIONS,
        ReadRowsRequest.getDefaultInstance(),
        readAsync,
        options,
        executorService,
        new Metadata(),
        clock);
  }
}
