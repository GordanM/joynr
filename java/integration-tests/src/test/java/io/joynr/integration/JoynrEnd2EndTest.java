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
package io.joynr.integration;

import io.joynr.provider.ProviderAnnotations;
import io.joynr.accesscontrol.StaticDomainAccessControlProvisioning;
import io.joynr.dispatching.subscription.SubscriptionTestsPublisher;
import io.joynr.provider.AbstractSubscriptionPublisher;
import io.joynr.provider.JoynrProvider;
import io.joynr.provider.SubscriptionPublisherFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import joynr.infrastructure.GlobalCapabilitiesDirectoryProvider;
import joynr.infrastructure.DacTypes.MasterAccessControlEntry;
import joynr.infrastructure.DacTypes.Permission;
import joynr.infrastructure.DacTypes.TrustLevel;
import joynr.tests.testProvider;

import org.mockito.Mockito;
import org.mockito.internal.matchers.InstanceOf;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.google.inject.AbstractModule;
import com.google.inject.Module;

public class JoynrEnd2EndTest {

    protected final SubscriptionPublisherFactory subscriptionPublisherFactory = Mockito.spy(new SubscriptionPublisherFactory());
    private final SubscriptionTestsPublisher testSubscriptionPublisher = new SubscriptionTestsPublisher();

    public JoynrEnd2EndTest() {
        Answer<AbstractSubscriptionPublisher> answer = new Answer<AbstractSubscriptionPublisher>() {

            @Override
            public AbstractSubscriptionPublisher answer(InvocationOnMock invocation) throws Throwable {
                Object provider = invocation.getArguments()[0];
                if (provider instanceof testProvider) {
                    ((testProvider) provider).setSubscriptionPublisher(testSubscriptionPublisher);
                }
                return testSubscriptionPublisher;
            }
        };
        Mockito.doAnswer(answer)
               .when(subscriptionPublisherFactory)
               .create((JoynrProvider) Mockito.argThat(new InstanceOf(testProvider.class)));
    }

    protected Module getSubscriptionPublisherFactoryModule() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(SubscriptionPublisherFactory.class).toInstance(subscriptionPublisherFactory);
            }
        };
    }

    protected SubscriptionTestsPublisher getSubscriptionTestsPublisher() {
        return testSubscriptionPublisher;
    }

    protected static void provisionPermissiveAccessControlEntry(String domain, String interfaceName) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enableDefaultTypingAsProperty(DefaultTyping.JAVA_LANG_OBJECT, "_typeName");
        List<MasterAccessControlEntry> provisionedAccessControlEntries = new ArrayList<MasterAccessControlEntry>();
        String existingAccessControlEntriesJson = System.getProperty(StaticDomainAccessControlProvisioning.PROPERTY_PROVISIONED_MASTER_ACCESSCONTROLENTRIES);
        if (existingAccessControlEntriesJson != null) {
            provisionedAccessControlEntries.addAll(Arrays.asList(objectMapper.readValue(existingAccessControlEntriesJson,
                                                                                        MasterAccessControlEntry[].class)));
        }

        MasterAccessControlEntry newMasterAccessControlEntry = new MasterAccessControlEntry("*",
                                                                                            domain,
                                                                                            interfaceName,
                                                                                            TrustLevel.LOW,
                                                                                            new TrustLevel[]{ TrustLevel.LOW },
                                                                                            TrustLevel.LOW,
                                                                                            new TrustLevel[]{ TrustLevel.LOW },
                                                                                            "*",
                                                                                            Permission.YES,
                                                                                            new Permission[]{ Permission.YES });

        provisionedAccessControlEntries.add(newMasterAccessControlEntry);
        String provisionedAccessControlEntriesAsJson = objectMapper.writeValueAsString(provisionedAccessControlEntries.toArray());
        System.setProperty(StaticDomainAccessControlProvisioning.PROPERTY_PROVISIONED_MASTER_ACCESSCONTROLENTRIES,
                           provisionedAccessControlEntriesAsJson);
    }

    protected static void provisionDiscoveryDirectoryAccessControlEntries() throws Exception {
        provisionPermissiveAccessControlEntry("io.joynr",
                                              ProviderAnnotations.getInterfaceName(GlobalCapabilitiesDirectoryProvider.class));
    }
}
