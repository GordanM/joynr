package io.joynr.dispatching;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2015 BMW Car IT GmbH
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

import static io.joynr.runtime.JoynrInjectionConstants.JOYNR_SCHEDULER_CLEANUP;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import io.joynr.common.ExpiryDate;
import io.joynr.dispatching.rpc.ReplyCaller;
import io.joynr.dispatching.rpc.ReplyCallerDirectory;
import io.joynr.dispatching.rpc.RpcUtils;
import io.joynr.exceptions.JoynrMessageNotSentException;
import io.joynr.exceptions.JoynrSendBufferFullException;
import io.joynr.messaging.routing.MessageRouter;
import io.joynr.proxy.Callback;
import io.joynr.proxy.JoynrMessagingConnectorFactory;
import io.joynr.security.PlatformSecurityManager;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import joynr.JoynrMessage;
import joynr.OneWay;
import joynr.Reply;
import joynr.Request;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;

/**
 * This test mocks the Http Communication Manager out and tests only the functionality contained in the Dispatcher.
 */
@RunWith(MockitoJUnitRunner.class)
public class RequestReplyManagerTest {
    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(RequestReplyManagerTest.class);
    private static final int TIME_OUT_MS = 10 * 1000;
    private static final long TIME_TO_LIVE = 10000L;
    private RequestReplyDispatcher requestReplyDispatcher;
    private RequestReplyManager requestReplyManager;
    private ReplyCallerDirectory replyCallerDirectory;
    private RequestCallerDirectory requestCallerDirectory;
    private String channelId;
    private String testSenderParticipantId;
    private String testMessageListenerParticipantId;
    private String testMessageResponderParticipantId;
    private String testListenerUnregisteredParticipantId;
    private String testResponderUnregisteredParticipantId;

    private JoynrMessage messageToResponder;
    private JoynrMessage messageToUnregisteredListener1;
    private JoynrMessage messageToUnregisteredListener2;

    private final String payload1 = "testPayload 1";
    private final String payload2 = "testPayload 2";
    private final String payload3 = "testPayload 3";

    private Request request1;
    private Request request2;
    private OneWay oneWay1;

    private ObjectMapper objectMapper;

    @Mock
    private MessageRouter messageRouterMock;
    @Mock
    private PlatformSecurityManager platformSecurityManagerMock;

    @Before
    public void setUp() throws NoSuchMethodException, SecurityException, JsonGenerationException, IOException {

        testMessageListenerParticipantId = "testMessageListenerParticipantId";
        testMessageResponderParticipantId = "testMessageResponderParticipantId";
        testSenderParticipantId = "testSenderParticipantId";
        testListenerUnregisteredParticipantId = "testListenerUnregisteredParticipantId";
        testResponderUnregisteredParticipantId = "testResponderUnregisteredParticipantId";

        channelId = "disTest-" + UUID.randomUUID().toString();
        Injector injector = Guice.createInjector(new AbstractModule() {

            @Override
            protected void configure() {
                bind(MessageRouter.class).toInstance(messageRouterMock);
                bind(RequestReplyDispatcher.class).to(RequestReplyDispatcherImpl.class);
                bind(RequestReplyManager.class).to(RequestReplyManagerImpl.class);
                bind(PlatformSecurityManager.class).toInstance(platformSecurityManagerMock);
                requestStaticInjection(RpcUtils.class, Request.class, JoynrMessagingConnectorFactory.class);

                ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("joynr.Cleanup-%d").build();
                ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(namedThreadFactory);
                bind(ScheduledExecutorService.class).annotatedWith(Names.named(JOYNR_SCHEDULER_CLEANUP))
                                                    .toInstance(cleanupExecutor);
            }
        });

        objectMapper = injector.getInstance(ObjectMapper.class);
        objectMapper.registerSubtypes(Request.class, OneWay.class);

        requestReplyDispatcher = injector.getInstance(RequestReplyDispatcher.class);
        requestCallerDirectory = injector.getInstance(RequestCallerDirectory.class);
        replyCallerDirectory = injector.getInstance(ReplyCallerDirectory.class);
        requestReplyManager = injector.getInstance(RequestReplyManager.class);

        // dispatcher.addListener(testMessageListenerParticipantId, testListener);

        // jsonRequestString1 = "{\"_typeName\":\"Request\", \"methodName\":\"respond\",\"params\":{\"payload\": \""
        // + payload1 + "\"}}";
        // jsonRequestString2 = "{\"_typeName\":\"Request\", \"methodName\":\"respond\",\"params\":{\"payload\": \""
        // + payload2 + "\"}}";

        Object[] params1 = new Object[]{ payload1 };
        Object[] params2 = new Object[]{ payload2 };

        // MethodMetaInformation methodMetaInformation = new
        // MethodMetaInformation(TestRequestCaller.class.getMethod("respond", new Class[]{ Object.class }));
        Method method = TestRequestCaller.class.getMethod("respond", new Class[]{ String.class });
        request1 = new Request(method.getName(), params1, method.getParameterTypes());
        request2 = new Request(method.getName(), params2, method.getParameterTypes());

        oneWay1 = new OneWay(payload1);
        Map<String, String> headerToResponder = Maps.newHashMap();
        headerToResponder.put(JoynrMessage.HEADER_NAME_FROM_PARTICIPANT_ID, testSenderParticipantId);
        headerToResponder.put(JoynrMessage.HEADER_NAME_TO_PARTICIPANT_ID, testMessageResponderParticipantId);
        headerToResponder.put(JoynrMessage.HEADER_NAME_CONTENT_TYPE, JoynrMessage.CONTENT_TYPE_TEXT_PLAIN);
        messageToResponder = new JoynrMessage();
        messageToResponder.setHeader(headerToResponder);
        messageToResponder.setType(JoynrMessage.MESSAGE_TYPE_REQUEST);
        messageToResponder.setPayload(payload1);

        Map<String, String> headerToUnregisteredListener = Maps.newHashMap();
        headerToUnregisteredListener.put(JoynrMessage.HEADER_NAME_FROM_PARTICIPANT_ID, testSenderParticipantId);
        headerToUnregisteredListener.put(JoynrMessage.HEADER_NAME_TO_PARTICIPANT_ID,
                                         testListenerUnregisteredParticipantId);

        messageToUnregisteredListener1 = new JoynrMessage();
        messageToUnregisteredListener1.setHeader(headerToUnregisteredListener);
        messageToUnregisteredListener1.setType(JoynrMessage.MESSAGE_TYPE_ONE_WAY);

        messageToUnregisteredListener1.setPayload(payload2);

        messageToUnregisteredListener2 = new JoynrMessage();
        messageToUnregisteredListener2.setHeader(headerToUnregisteredListener);
        messageToUnregisteredListener2.setType(JoynrMessage.MESSAGE_TYPE_ONE_WAY);

        messageToUnregisteredListener2.setPayload(payload3);

        Map<String, String> requestHeader = Maps.newHashMap();
        requestHeader.put(JoynrMessage.HEADER_NAME_FROM_PARTICIPANT_ID, testSenderParticipantId);
        requestHeader.put(JoynrMessage.HEADER_NAME_TO_PARTICIPANT_ID, testResponderUnregisteredParticipantId);
        requestHeader.put(JoynrMessage.HEADER_NAME_EXPIRY_DATE, String.valueOf(System.currentTimeMillis()
                + TIME_TO_LIVE));
        requestHeader.put(JoynrMessage.HEADER_NAME_CONTENT_TYPE, JoynrMessage.CONTENT_TYPE_APPLICATION_JSON);
        requestHeader.put(JoynrMessage.HEADER_NAME_REPLY_CHANNELID, channelId);
    }

    @After
    public void tearDown() {
        requestReplyDispatcher.removeListener(testMessageListenerParticipantId);
        requestCallerDirectory.removeCaller(testMessageResponderParticipantId);
    }

    @Test
    public void oneWayMessagesAreSentToTheCommunicationManager() throws Exception {
        requestReplyManager.sendOneWay(testSenderParticipantId,
                                       testMessageListenerParticipantId,
                                       payload1,
                                       TIME_TO_LIVE);

        ArgumentCaptor<JoynrMessage> messageCapture = ArgumentCaptor.forClass(JoynrMessage.class);
        verify(messageRouterMock, Mockito.times(1)).route(messageCapture.capture());
        assertEquals(messageCapture.getValue().getHeaderValue(JoynrMessage.HEADER_NAME_FROM_PARTICIPANT_ID),
                     testSenderParticipantId);
        assertEquals(messageCapture.getValue().getHeaderValue(JoynrMessage.HEADER_NAME_TO_PARTICIPANT_ID),
                     testMessageListenerParticipantId);

        assertEquals(messageCapture.getValue().getPayload(), payload1);
    }

    @Test
    public void requestMessagesSentToTheCommunicationManager() throws Exception {
        requestReplyManager.sendRequest(testSenderParticipantId,
                                        testMessageResponderParticipantId,
                                        request1,
                                        TIME_TO_LIVE);

        ArgumentCaptor<JoynrMessage> messageCapture = ArgumentCaptor.forClass(JoynrMessage.class);
        verify(messageRouterMock, Mockito.times(1)).route(messageCapture.capture());
        assertEquals(messageCapture.getValue().getHeaderValue(JoynrMessage.HEADER_NAME_FROM_PARTICIPANT_ID),
                     testSenderParticipantId);
        assertEquals(messageCapture.getValue().getHeaderValue(JoynrMessage.HEADER_NAME_TO_PARTICIPANT_ID),
                     testMessageResponderParticipantId);

        assertEquals(messageCapture.getValue().getPayload(), objectMapper.writeValueAsString(request1));
    }

    private abstract class ReplyCallback extends Callback<Reply> {
    }

    @Test
    public void requestCallerInvokedForIncomingRequest() throws Exception {
        TestRequestCaller testRequestCallerSpy = Mockito.spy(new TestRequestCaller(1));

        requestCallerDirectory.addCaller(testMessageResponderParticipantId, testRequestCallerSpy);
        ReplyCallback replyCallbackMock = mock(ReplyCallback.class);
        requestReplyDispatcher.handleRequest(replyCallbackMock,
                                             testMessageResponderParticipantId,
                                             request1,
                                             TIME_TO_LIVE);

        String reply = (String) testRequestCallerSpy.getSentPayloadFor(request1);

        ArgumentCaptor<Reply> replyCapture = ArgumentCaptor.forClass(Reply.class);
        verify(testRequestCallerSpy).respond(Mockito.eq(payload1));
        verify(replyCallbackMock).onSuccess(replyCapture.capture());
        assertEquals(reply, replyCapture.getValue().getResponse().get(0));
    }

    @Test
    public void replyCallerInvokedForIncomingReply() throws Exception {
        ReplyCaller replyCaller = mock(ReplyCaller.class);
        replyCallerDirectory.addReplyCaller(request1.getRequestReplyId(),
                                            replyCaller,
                                            ExpiryDate.fromRelativeTtl(TIME_TO_LIVE * 2));

        Reply reply = new Reply(request1.getRequestReplyId(), payload1);
        requestReplyDispatcher.handleReply(reply);

        verify(replyCaller).messageCallBack(reply);
    }

    @Test
    public void queueMessagesForUnregisteredResponder() throws InterruptedException {
        ReplyCallback replyCallbackMock = mock(ReplyCallback.class);

        requestReplyDispatcher.handleRequest(replyCallbackMock,
                                             testResponderUnregisteredParticipantId,
                                             request1,
                                             ExpiryDate.fromRelativeTtl((int) (TIME_TO_LIVE * 0.03)).getValue());
        requestReplyDispatcher.handleRequest(replyCallbackMock,
                                             testResponderUnregisteredParticipantId,
                                             request2,
                                             ExpiryDate.fromRelativeTtl((int) (TIME_TO_LIVE * 5)).getValue());

        Thread.sleep((long) (TIME_TO_LIVE * 0.03 + 20));
        TestRequestCaller testResponderUnregistered = new TestRequestCaller(1);

        testResponderUnregistered.waitForMessage((int) (TIME_TO_LIVE * 0.05));
        requestCallerDirectory.addCaller(testResponderUnregisteredParticipantId, testResponderUnregistered);

        testResponderUnregistered.assertAllPayloadsReceived((int) (TIME_TO_LIVE));
        testResponderUnregistered.assertReceivedPayloadsContainsNot(payload1);
        testResponderUnregistered.assertReceivedPayloadsContains(payload2);
    }

    @Test
    public void requestReplyMessagesRemoveCallBackByTtl() throws Exception {
        TestRequestCaller testResponder = new TestRequestCaller(1);
        ExpiryDate ttlReplyCaller = ExpiryDate.fromRelativeTtl(1000L);

        final ReplyCaller replyCaller = mock(ReplyCaller.class);
        replyCallerDirectory.addReplyCaller(request1.getRequestReplyId(), replyCaller, ttlReplyCaller);

        Thread.sleep(ttlReplyCaller.getRelativeTtl() + 100);
        requestReplyDispatcher.handleReply(new Reply(request1.getRequestReplyId(),
                                                     testResponder.getSentPayloadFor(request1)));

        verify(replyCaller, never()).messageCallBack(any(Reply.class));
    }

    @Test
    public void sendOneWayTtl() throws JoynrMessageNotSentException, JoynrSendBufferFullException,
                               JsonGenerationException, JsonMappingException, IOException {

        TestOneWayRecipient oneWayRecipient = new TestOneWayRecipient(1);
        requestReplyDispatcher.addOneWayRecipient(testMessageListenerParticipantId, oneWayRecipient);

        requestReplyDispatcher.handleOneWayRequest(testMessageListenerParticipantId, oneWay1, TIME_TO_LIVE);

        oneWayRecipient.assertAllPayloadsReceived(TIME_OUT_MS);
    }

    @Test
    @Ignore
    public void requestReplyRoundtrip() throws JoynrMessageNotSentException, JoynrSendBufferFullException,
                                       JsonGenerationException, JsonMappingException, IOException {
        /*
         * This test is not a unit test, but an integration test. We already have such integration tests, so this test is obsolete
        TestRequestCaller testResponder = new TestRequestCaller(1);
        requestCallerDirectory.addCaller(testMessageResponderParticipantId, testResponder);
        ReplyCaller replyCaller = mock(ReplyCaller.class);
        requestReplyDispatcher.addReplyCaller(request1.getRequestReplyId(), replyCaller, TIME_TO_LIVE * 2);

        requestReplyManager.sendRequest(testSenderParticipantId,
                                        testMessageResponderParticipantId,
                                        request1,
                                        TIME_TO_LIVE);

        testResponder.assertAllPayloadsReceived(20);
        assertEquals(2, messageSenderReceiverMock.getSentMessages().size());
         */
    }
}
