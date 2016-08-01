package io.joynr.capabilities;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2013 BMW Car IT GmbH
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

import static io.joynr.messaging.MessagingPropertyKeys.CHANNELID;
import static java.lang.String.format;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import io.joynr.exceptions.JoynrRuntimeException;
import io.joynr.messaging.inprocess.InProcessAddress;
import io.joynr.messaging.routing.RoutingTable;
import joynr.infrastructure.GlobalCapabilitiesDirectory;
import joynr.infrastructure.GlobalDomainAccessController;
import joynr.system.RoutingTypes.Address;
import joynr.system.RoutingTypes.ChannelAddress;
import joynr.system.RoutingTypes.MqttAddress;
import joynr.types.DiscoveryEntry;
import joynr.types.GlobalDiscoveryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a set of JSON encoded {@link GlobalDiscoveryEntry discovery entries} from the file referenced by the property
 * named {@link #PROPERTY_PROVISIONED_CAPABILITIES_FILE joynr.capabilities.provisioned.file} and makes them available
 * via {@link #getDiscoveryEntries()}.
 *
 * This component will fail-fast - that is, it will throw a {@link JoynrRuntimeException} during initialization if the
 * JSON read from the file the property refers to cannot bit parsed, or the file cannot be found.
 */
public class StaticCapabilitiesProvisioning implements CapabilitiesProvisioning {

    public static final String PROPERTY_PROVISIONED_CAPABILITIES_FILE = "joynr.capabilities.provisioned.file";
    private static Logger logger = LoggerFactory.getLogger(StaticCapabilitiesProvisioning.class);
    private final ResourceContentProvider resourceContentProvider;

    private Collection<DiscoveryEntry> discoveryEntries;

    @Inject
    public StaticCapabilitiesProvisioning(@Named(PROPERTY_PROVISIONED_CAPABILITIES_FILE) String provisionedCapabilitiesFile,
                                          @Named(CHANNELID) String localChannelId,
                                          ObjectMapper objectMapper,
                                          RoutingTable routingTable,
                                          LegacyCapabilitiesProvisioning legacyCapabilitiesProvisioning,
                                          ResourceContentProvider resourceContentProvider) {
        discoveryEntries = new HashSet<DiscoveryEntry>();
        this.resourceContentProvider = resourceContentProvider;
        addEntriesFromJson(provisionedCapabilitiesFile, objectMapper, localChannelId);
        logger.debug("{} provisioned discovery entries loaded from JSON: {}", discoveryEntries.size(), discoveryEntries);
        overrideEntriesFromLegacySettings(legacyCapabilitiesProvisioning);
        logger.debug("{} provisioned discovery entries after adding legacy entries: {}",
                     discoveryEntries.size(),
                     discoveryEntries);
        logger.info("Statically provisioned discovery entries loaded: {}", discoveryEntries);
        addAddressesToRoutingTable(routingTable);
    }

    private void addAddressesToRoutingTable(RoutingTable routingTable) {
        for (DiscoveryEntry discoveryEntry : discoveryEntries) {
            if (discoveryEntry instanceof GlobalDiscoveryEntry) {
                GlobalDiscoveryEntry globalDiscoveryEntry = (GlobalDiscoveryEntry) discoveryEntry;
                routingTable.put(globalDiscoveryEntry.getParticipantId(),
                                 CapabilityUtils.getAddressFromGlobalDiscoveryEntry(globalDiscoveryEntry));
            }
        }
    }

    private void overrideEntriesFromLegacySettings(LegacyCapabilitiesProvisioning legacyCapabilitiesProvisioning) {
        DiscoveryEntry globalCapabilitiesEntry = legacyCapabilitiesProvisioning.getDiscoveryEntryForInterface(GlobalCapabilitiesDirectory.class);
        if (globalCapabilitiesEntry != null) {
            removeExistingEntryForInterface(GlobalCapabilitiesDirectory.INTERFACE_NAME);
            discoveryEntries.add(globalCapabilitiesEntry);
        }
        DiscoveryEntry domainAccessControllerEntry = legacyCapabilitiesProvisioning.getDiscoveryEntryForInterface(GlobalDomainAccessController.class);
        if (domainAccessControllerEntry != null) {
            removeExistingEntryForInterface(GlobalDomainAccessController.INTERFACE_NAME);
            discoveryEntries.add(domainAccessControllerEntry);
        }
    }

    private void removeExistingEntryForInterface(String interfaceName) {
        DiscoveryEntry entryToRemove = null;
        for (DiscoveryEntry discoveryEntry : discoveryEntries) {
            if (discoveryEntry instanceof GlobalDiscoveryEntry
                    && interfaceName.equals(((GlobalDiscoveryEntry) discoveryEntry).getInterfaceName())) {
                entryToRemove = discoveryEntry;
                break;
            }
        }
        if (entryToRemove != null) {
            discoveryEntries.remove(entryToRemove);
        }
    }

    private void addEntriesFromJson(String provisionedCapabilitiesJsonFilename,
                                    ObjectMapper objectMapper,
                                    String localChannelId) {
        String provisionedCapabilitiesJsonString = resourceContentProvider.readFromFileOrResource(provisionedCapabilitiesJsonFilename);
        logger.debug("Statically provisioned capabilities JSON read: {}", provisionedCapabilitiesJsonString);
        List<GlobalDiscoveryEntry> newEntries = null;
        try {
            newEntries = objectMapper.readValue(provisionedCapabilitiesJsonString,
                                                new TypeReference<List<GlobalDiscoveryEntry>>() {
                                                });
            for (GlobalDiscoveryEntry globalDiscoveryEntry : newEntries) {
                globalDiscoveryEntry.setLastSeenDateMs(System.currentTimeMillis());
                Address address = CapabilityUtils.getAddressFromGlobalDiscoveryEntry(globalDiscoveryEntry);
                substituteInProcessAddressIfLocal(objectMapper, localChannelId, globalDiscoveryEntry, address);
                discoveryEntries.add(globalDiscoveryEntry);
            }
        } catch (IOException e) {
            String message = format("Unable to load provisioned capabilities. Invalid JSON value: %s",
                                    provisionedCapabilitiesJsonString);
            throw new JoynrRuntimeException(message, e);
        }
    }

    private void substituteInProcessAddressIfLocal(ObjectMapper objectMapper,
                                                   String localChannelId,
                                                   GlobalDiscoveryEntry globalDiscoveryEntry,
                                                   Address address) throws JsonProcessingException {
        if ((address instanceof ChannelAddress && localChannelId.equals(((ChannelAddress) address).getChannelId()))
                || (address instanceof MqttAddress && localChannelId.equals(((MqttAddress) address).getTopic()))) {
            Address localAddress = new InProcessAddress();
            globalDiscoveryEntry.setAddress(objectMapper.writeValueAsString(localAddress));
        }
    }

    @Override
    public Collection<DiscoveryEntry> getDiscoveryEntries() {
        return discoveryEntries;
    }

}
