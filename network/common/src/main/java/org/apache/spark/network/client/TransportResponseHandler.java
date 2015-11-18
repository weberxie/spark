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

package org.apache.spark.network.client;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.network.protocol.ChunkFetchFailure;
import org.apache.spark.network.protocol.ChunkFetchSuccess;
import org.apache.spark.network.protocol.ResponseMessage;
import org.apache.spark.network.protocol.RpcFailure;
import org.apache.spark.network.protocol.RpcResponse;
import org.apache.spark.network.protocol.StreamChunkId;
import org.apache.spark.network.protocol.StreamFailure;
import org.apache.spark.network.protocol.StreamResponse;
import org.apache.spark.network.server.MessageHandler;
import org.apache.spark.network.util.NettyUtils;
import org.apache.spark.network.util.TransportFrameDecoder;

/**
 * Handler that processes server responses, in response to requests issued from a
 * [[TransportClient]]. It works by tracking the list of outstanding requests (and their callbacks).
 *
 * Concurrency: thread safe and can be called from multiple threads.
 */
public class TransportResponseHandler extends MessageHandler<ResponseMessage> {
  private final Logger logger = LoggerFactory.getLogger(TransportResponseHandler.class);

  private final Channel channel;

  private final Map<StreamChunkId, ChunkReceivedCallback> outstandingFetches;

  private final Map<Long, RpcResponseCallback> outstandingRpcs;

  private final Queue<StreamCallback> streamCallbacks;

  /** Records the time (in system nanoseconds) that the last fetch or RPC request was sent. */
  private final AtomicLong timeOfLastRequestNs;

  public TransportResponseHandler(Channel channel) {
    this.channel = channel;
    this.outstandingFetches = new ConcurrentHashMap<StreamChunkId, ChunkReceivedCallback>();
    this.outstandingRpcs = new ConcurrentHashMap<Long, RpcResponseCallback>();
    this.streamCallbacks = new ConcurrentLinkedQueue<StreamCallback>();
    this.timeOfLastRequestNs = new AtomicLong(0);
  }

  public void addFetchRequest(StreamChunkId streamChunkId, ChunkReceivedCallback callback) {
    timeOfLastRequestNs.set(System.nanoTime());
    outstandingFetches.put(streamChunkId, callback);
  }

  public void removeFetchRequest(StreamChunkId streamChunkId) {
    outstandingFetches.remove(streamChunkId);
  }

  public void addRpcRequest(long requestId, RpcResponseCallback callback) {
    timeOfLastRequestNs.set(System.nanoTime());
    outstandingRpcs.put(requestId, callback);
  }

  public void removeRpcRequest(long requestId) {
    outstandingRpcs.remove(requestId);
  }

  public void addStreamCallback(StreamCallback callback) {
    streamCallbacks.offer(callback);
  }

  /**
   * Fire the failure callback for all outstanding requests. This is called when we have an
   * uncaught exception or pre-mature connection termination.
   */
  private void failOutstandingRequests(Throwable cause) {
    for (Map.Entry<StreamChunkId, ChunkReceivedCallback> entry : outstandingFetches.entrySet()) {
      entry.getValue().onFailure(entry.getKey().chunkIndex, cause);
    }
    for (Map.Entry<Long, RpcResponseCallback> entry : outstandingRpcs.entrySet()) {
      entry.getValue().onFailure(cause);
    }

    // It's OK if new fetches appear, as they will fail immediately.
    outstandingFetches.clear();
    outstandingRpcs.clear();
  }

  @Override
  public void channelUnregistered() {
    if (numOutstandingRequests() > 0) {
      String remoteAddress = NettyUtils.getRemoteAddress(channel);
      logger.error("Still have {} requests outstanding when connection from {} is closed",
        numOutstandingRequests(), remoteAddress);
      failOutstandingRequests(new IOException("Connection from " + remoteAddress + " closed"));
    }
  }

  @Override
  public void exceptionCaught(Throwable cause) {
    if (numOutstandingRequests() > 0) {
      String remoteAddress = NettyUtils.getRemoteAddress(channel);
      logger.error("Still have {} requests outstanding when connection from {} is closed",
        numOutstandingRequests(), remoteAddress);
      failOutstandingRequests(cause);
    }
  }

  @Override
  public void handle(ResponseMessage message) {
    String remoteAddress = NettyUtils.getRemoteAddress(channel);
    if (message instanceof ChunkFetchSuccess) {
      ChunkFetchSuccess resp = (ChunkFetchSuccess) message;
      ChunkReceivedCallback listener = outstandingFetches.get(resp.streamChunkId);
      if (listener == null) {
        logger.warn("Ignoring response for block {} from {} since it is not outstanding",
          resp.streamChunkId, remoteAddress);
        resp.body.release();
      } else {
        outstandingFetches.remove(resp.streamChunkId);
        listener.onSuccess(resp.streamChunkId.chunkIndex, resp.body);
        resp.body.release();
      }
    } else if (message instanceof ChunkFetchFailure) {
      ChunkFetchFailure resp = (ChunkFetchFailure) message;
      ChunkReceivedCallback listener = outstandingFetches.get(resp.streamChunkId);
      if (listener == null) {
        logger.warn("Ignoring response for block {} from {} ({}) since it is not outstanding",
          resp.streamChunkId, remoteAddress, resp.errorString);
      } else {
        outstandingFetches.remove(resp.streamChunkId);
        listener.onFailure(resp.streamChunkId.chunkIndex, new ChunkFetchFailureException(
          "Failure while fetching " + resp.streamChunkId + ": " + resp.errorString));
      }
    } else if (message instanceof RpcResponse) {
      RpcResponse resp = (RpcResponse) message;
      RpcResponseCallback listener = outstandingRpcs.get(resp.requestId);
      if (listener == null) {
        logger.warn("Ignoring response for RPC {} from {} ({} bytes) since it is not outstanding",
          resp.requestId, remoteAddress, resp.response.length);
      } else {
        outstandingRpcs.remove(resp.requestId);
        listener.onSuccess(resp.response);
      }
    } else if (message instanceof RpcFailure) {
      RpcFailure resp = (RpcFailure) message;
      RpcResponseCallback listener = outstandingRpcs.get(resp.requestId);
      if (listener == null) {
        logger.warn("Ignoring response for RPC {} from {} ({}) since it is not outstanding",
          resp.requestId, remoteAddress, resp.errorString);
      } else {
        outstandingRpcs.remove(resp.requestId);
        listener.onFailure(new RuntimeException(resp.errorString));
      }
    } else if (message instanceof StreamResponse) {
      StreamResponse resp = (StreamResponse) message;
      StreamCallback callback = streamCallbacks.poll();
      if (callback != null) {
        StreamInterceptor interceptor = new StreamInterceptor(resp.streamId, resp.byteCount,
          callback);
        try {
          TransportFrameDecoder frameDecoder = (TransportFrameDecoder)
            channel.pipeline().get(TransportFrameDecoder.HANDLER_NAME);
          frameDecoder.setInterceptor(interceptor);
        } catch (Exception e) {
          logger.error("Error installing stream handler.", e);
        }
      } else {
        logger.error("Could not find callback for StreamResponse.");
      }
    } else if (message instanceof StreamFailure) {
      StreamFailure resp = (StreamFailure) message;
      StreamCallback callback = streamCallbacks.poll();
      if (callback != null) {
        try {
          callback.onFailure(resp.streamId, new RuntimeException(resp.error));
        } catch (IOException ioe) {
          logger.warn("Error in stream failure handler.", ioe);
        }
      } else {
        logger.warn("Stream failure with unknown callback: {}", resp.error);
      }
    } else {
      throw new IllegalStateException("Unknown response type: " + message.type());
    }
  }

  /** Returns total number of outstanding requests (fetch requests + rpcs) */
  public int numOutstandingRequests() {
    return outstandingFetches.size() + outstandingRpcs.size();
  }

  /** Returns the time in nanoseconds of when the last request was sent out. */
  public long getTimeOfLastRequestNs() {
    return timeOfLastRequestNs.get();
  }

}
