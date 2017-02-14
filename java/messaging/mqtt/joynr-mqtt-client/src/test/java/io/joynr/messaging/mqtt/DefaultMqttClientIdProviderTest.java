package io.joynr.messaging.mqtt;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2017 BMW Car IT GmbH
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

import static org.junit.Assert.assertEquals;

import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.name.Names;
import com.google.inject.util.Modules;

import io.joynr.common.JoynrPropertiesModule;
import io.joynr.messaging.MessagingPropertyKeys;
import io.joynr.messaging.mqtt.paho.client.MqttPahoModule;
import io.joynr.messaging.routing.MessageRouter;

/**
 * Unit tests for {@link DefaultMqttClientIdProvider}.
 */
public class DefaultMqttClientIdProviderTest {

    private String receiverId = "testReceiverId123";
    private String clientIdPrefix = "testPrefix-";
    private MqttClientIdProvider clientIdProviderWithoutClientIdPrefix;
    private MqttClientIdProvider clientIdProviderWithClientIdPrefix;

    @Mock
    private MessageRouter mockMessageRouter;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        Properties properties = new Properties();
        properties.put(MqttModule.PROPERTY_KEY_MQTT_RECONNECT_SLEEP_MS, "100");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_BROKER_URI, "tcp://localhost:1883");
        properties.put(MessagingPropertyKeys.RECEIVERID, receiverId);
        Module testModule = Modules.override(new MqttPahoModule()).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(MessageRouter.class).toInstance(mockMessageRouter);
                bind(ScheduledExecutorService.class).annotatedWith(Names.named(MessageRouter.SCHEDULEDTHREADPOOL))
                                                    .toInstance(Executors.newScheduledThreadPool(10));
            }
        });
        Injector injectorWithoutClientIdPrefix = Guice.createInjector(testModule, new JoynrPropertiesModule(properties));
        clientIdProviderWithoutClientIdPrefix = injectorWithoutClientIdPrefix.getInstance(MqttClientIdProvider.class);

        properties.put(MqttModule.PROPERTY_KEY_MQTT_CLIENT_ID_PREFIX, clientIdPrefix);
        Injector injectorWithClientIdPrefix = Guice.createInjector(testModule, new JoynrPropertiesModule(properties));
        clientIdProviderWithClientIdPrefix = injectorWithClientIdPrefix.getInstance(MqttClientIdProvider.class);
    }

    @Test
    public void testGetClientIdWithoutPrefix() {
        String clientId = clientIdProviderWithoutClientIdPrefix.getClientId();
        String expectedClientId = "joynr:" + receiverId;
        assertEquals(expectedClientId, clientId);
    }

    @Test
    public void testGetClientIdWithPrefix() {
        String clientId = clientIdProviderWithClientIdPrefix.getClientId();
        String expectedClientId = clientIdPrefix + "joynr:" + receiverId;
        assertEquals(expectedClientId, clientId);
    }

}
