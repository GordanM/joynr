package io.joynr.integration.websocket;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2016 BMW Car IT GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;

import io.joynr.dispatching.JoynrMessageFactory;
import io.joynr.messaging.FailureAction;
import io.joynr.messaging.JoynrMessageProcessor;
import io.joynr.messaging.MessagingQos;
import io.joynr.messaging.routing.MessageRouter;
import io.joynr.messaging.websocket.WebSocketClientMessagingStubFactory;
import io.joynr.messaging.websocket.WebSocketEndpointFactory;
import io.joynr.messaging.websocket.WebSocketMessagingSkeleton;
import io.joynr.messaging.websocket.WebSocketMessagingStub;
import io.joynr.messaging.websocket.jetty.client.WebSocketJettyClientFactory;
import io.joynr.messaging.websocket.server.WebSocketJettyServerFactory;
import io.joynr.servlet.ServletUtil;
import joynr.JoynrMessage;
import joynr.OneWayRequest;
import joynr.system.RoutingTypes.WebSocketAddress;
import joynr.system.RoutingTypes.WebSocketClientAddress;
import joynr.system.RoutingTypes.WebSocketProtocol;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.AdditionalAnswers.returnsFirstArg;

@RunWith(MockitoJUnitRunner.class)
public class WebSocketTest {
    private static Logger logger = LoggerFactory.getLogger(WebSocketTest.class);
    private WebSocketMessagingStub webSocketMessagingStub;
    private WebSocketMessagingSkeleton ccWebSocketMessagingSkeleton;
    private WebSocketAddress serverAddress;

    private JoynrMessageFactory joynrMessageFactory;
    @Mock
    private WebSocketClientMessagingStubFactory webSocketMessagingStubFactory;
    private WebSocketMessagingSkeleton libWebSocketMessagingSkeleton;

    @Mock
    MessageRouter messageRouterMock;
    private int port;
    private WebSocketClientAddress ownAddress;
    private WebSocketJettyClientFactory webSocketJettyClientFactory;

    @Before
    public void init() throws IOException {
        logger.debug("INIT WebsocketTest");
        port = ServletUtil.findFreePort();
        serverAddress = new WebSocketAddress(WebSocketProtocol.WS, "localhost", port, "/test");
        Mockito.doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                logger.debug("message arrived: " + invocationOnMock.getArguments().toString());
                return null;
            }
        }).when(messageRouterMock).route(Mockito.any(JoynrMessage.class));
        joynrMessageFactory = new JoynrMessageFactory(new ObjectMapper(), new HashSet<JoynrMessageProcessor>());
    }

    private void configure(int maxMessageSize,
                           long reconnectDelay,
                           long websocketIdleTimeout,
                           Set<JoynrMessageProcessor> messageProcessor) {
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketEndpointFactory webSocketJettyServerFactory = new WebSocketJettyServerFactory(maxMessageSize,
                                                                                               objectMapper);
        ccWebSocketMessagingSkeleton = new WebSocketMessagingSkeleton(serverAddress,
                                                                      webSocketJettyServerFactory,
                                                                      messageRouterMock,
                                                                      objectMapper,
                                                                      new WebSocketMessagingSkeleton.MainTransportFlagBearer(),
                                                                      messageProcessor);

        ownAddress = new WebSocketClientAddress(UUID.randomUUID().toString());
        webSocketJettyClientFactory = new WebSocketJettyClientFactory(ownAddress,
                                                                      maxMessageSize,
                                                                      reconnectDelay,
                                                                      websocketIdleTimeout,
                                                                      objectMapper);
        webSocketMessagingStub = new WebSocketMessagingStub(serverAddress,
                                                            webSocketJettyClientFactory.create(serverAddress),
                                                            new ObjectMapper());
        libWebSocketMessagingSkeleton = new WebSocketMessagingSkeleton(serverAddress,
                                                                       webSocketJettyClientFactory,
                                                                       messageRouterMock,
                                                                       new ObjectMapper(),
                                                                       new WebSocketMessagingSkeleton.MainTransportFlagBearer(),
                                                                       messageProcessor);
        ccWebSocketMessagingSkeleton.init();
        libWebSocketMessagingSkeleton.init();
    }

    @After
    public void stop() throws Exception {
        Thread.sleep(1000); // wait a short time to let the server finish
        logger.debug("Stopping websockets...");
        ccWebSocketMessagingSkeleton.shutdown();
        libWebSocketMessagingSkeleton.shutdown();
        logger.debug("websockets stopped");
    }

    @Test
    public void testSendMessage() throws Throwable {
        int maxMessageSize = 100000;
        long reconnectDelay = 100;
        long websocketIdleTimeout = 30000;
        configure(maxMessageSize, reconnectDelay, websocketIdleTimeout, new HashSet<JoynrMessageProcessor>());
        sendMessage();
    }

    @Test
    public void testWebsocketTimeoutReconnects() throws Throwable {

        int millis = 1000;
        int maxMessageSize = 100000;
        long reconnectDelay = 100;
        long websocketIdleTimeout = millis - 100;
        configure(maxMessageSize, reconnectDelay, websocketIdleTimeout, new HashSet<JoynrMessageProcessor>());

        sendMessage();
        logger.info("Waiting for " + millis + "ms to cause websocket idle timeout");
        Thread.sleep(millis);
        sendMessage();
    }

    @Test
    public void testJoynrMessageProcessorIsCalled() throws Throwable {
        JoynrMessageProcessor processorMock = mock(JoynrMessageProcessor.class);
        when(processorMock.processIncoming(any(JoynrMessage.class))).then(returnsFirstArg());

        int millis = 1000;
        int maxMessageSize = 100000;
        long reconnectDelay = 100;
        long websocketIdleTimeout = millis - 100;
        configure(maxMessageSize, reconnectDelay, websocketIdleTimeout, Sets.newHashSet(processorMock));
        sendMessage();
        Thread.sleep(millis);

        verify(processorMock).processIncoming(any(JoynrMessage.class));
    }

    private void sendMessage() throws Throwable {
        OneWayRequest request = new OneWayRequest("method", new Object[0], new Class<?>[0]);
        MessagingQos messagingQos = new MessagingQos(100000);
        JoynrMessage msg = joynrMessageFactory.createOneWayRequest("fromID", "toID", request, messagingQos);

        webSocketMessagingStub.transmit(msg, new FailureAction() {

            @Override
            public void execute(Throwable error) {
                Assert.fail(error.getMessage());
            }
        });
        Mockito.verify(messageRouterMock, Mockito.timeout(1000)).route(msg);
    }
}
