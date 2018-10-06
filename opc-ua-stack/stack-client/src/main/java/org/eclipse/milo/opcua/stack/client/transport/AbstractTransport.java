/*
 * Copyright (c) 2018 Kevin Herron
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.html.
 */

package org.eclipse.milo.opcua.stack.client.transport;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import org.eclipse.milo.opcua.stack.client.UaStackClientConfig;
import org.eclipse.milo.opcua.stack.core.Stack;
import org.eclipse.milo.opcua.stack.core.serialization.UaRequestMessage;
import org.eclipse.milo.opcua.stack.core.serialization.UaResponseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractTransport implements UaTransport {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final UaStackClientConfig config;

    public AbstractTransport(UaStackClientConfig config) {
        this.config = config;
    }

    /**
     * Get a {@link Channel} suitable for sending a request on.
     * <p>
     * The Channel must have a handler capable of matching inbound responses to pending outbound
     * {@link UaTransportRequest}s.
     *
     * @return a {@link Channel} suitable for sending a request on.
     */
    public abstract CompletableFuture<Channel> channel();

    @Override
    public CompletableFuture<UaResponseMessage> sendRequest(UaRequestMessage request) {
        return channel().thenCompose(channel -> sendRequest(request, channel, true));
    }

    private CompletableFuture<UaResponseMessage> sendRequest(
        UaRequestMessage request, Channel channel, boolean firstAttempt) {

        UaTransportRequest transportRequest = new UaTransportRequest(request);

        channel.writeAndFlush(transportRequest).addListener(f -> {
            if (!f.isSuccess()) {
                Throwable cause = f.cause();

                if (cause instanceof ClosedChannelException && firstAttempt) {
                    logger.debug("Write failed, channel closed; retrying...");

                    Stack.sharedScheduledExecutor().schedule(
                        () -> config.getExecutor().execute(() -> {
                            CompletableFuture<UaResponseMessage> sendAgain =
                                channel().thenCompose(ch -> sendRequest(request, ch, false));

                            sendAgain.whenComplete((r, ex) -> {
                                if (r != null) {
                                    transportRequest.getFuture().complete(r);
                                } else {
                                    transportRequest.getFuture().completeExceptionally(ex);
                                }
                            });
                        }),
                        1,
                        TimeUnit.SECONDS
                    );
                } else {
                    transportRequest.getFuture().completeExceptionally(cause);

                    logger.debug(
                        "Write failed, request={}, requestHandle={}",
                        request.getClass().getSimpleName(),
                        request.getRequestHeader().getRequestHandle());
                }
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace(
                        "Write succeeded for request={}, requestHandle={}",
                        request.getClass().getSimpleName(),
                        request.getRequestHeader().getRequestHandle());
                }
            }
        });

        return transportRequest.getFuture();
    }

}
