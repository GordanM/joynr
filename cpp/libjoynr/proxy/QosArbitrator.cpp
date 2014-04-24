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
#include "joynr/QosArbitrator.h"

#include "joynr/system/IDiscovery.h"
#include "joynr/system/DiscoveryEntry.h"
#include "joynr/DiscoveryQos.h"
#include "joynr/RequestStatus.h"
#include "joynr/types/ProviderQos.h"

#include <cassert>

namespace joynr {

using namespace joynr_logging;

Logger* QosArbitrator::logger = joynr_logging::Logging::getInstance()->getLogger("Arbi", "QosArbitrator");


QosArbitrator::QosArbitrator(
        const QString& domain,
        const QString& interfaceName,
        joynr::system::IDiscoverySync& discoveryProxy,
        const DiscoveryQos &discoveryQos
) :
    ProviderArbitrator(domain, interfaceName, discoveryProxy, discoveryQos),
    keyword(discoveryQos.getCustomParameter("keyword").getValue())
{
}


void QosArbitrator::attemptArbitration()
{
    joynr::RequestStatus status;
    QList<joynr::system::DiscoveryEntry> result;
    discoveryProxy.lookup(
                status,
                result,
                domain,
                interfaceName,
                systemDiscoveryQos
    );
    if(status.successful()) {
        receiveCapabilitiesLookupResults(result);
    } else {
        LOG_ERROR(
                    logger,
                    QString("Unable to lookup provider (domain: %1, interface: %2) "
                            "from discovery. Status code: %3."
                    )
                    .arg(domain)
                    .arg(interfaceName)
                    .arg(status.getCode().toString())
        );
    }
}


// Returns true if arbitration was successful, false otherwise
void QosArbitrator::receiveCapabilitiesLookupResults(
        const QList<joynr::system::DiscoveryEntry>& discoveryEntries
) {
    QString res = "";
    joynr::system::CommunicationMiddleware::Enum preferredConnection(joynr::system::CommunicationMiddleware::NONE);

    // Check for empty results
    if (discoveryEntries.size() == 0) return;

    qint64 highestPriority = -1;
    QListIterator<joynr::system::DiscoveryEntry> discoveryEntriesIterator(discoveryEntries);
    while (discoveryEntriesIterator.hasNext()) {
        joynr::system::DiscoveryEntry discoveryEntry = discoveryEntriesIterator.next();
        types::ProviderQos providerQos = discoveryEntry.getQos();
        LOG_TRACE(logger,"Looping over capabilitiesEntry: " + discoveryEntry.toString());
        if ( discoveryQos.getProviderMustSupportOnChange() &&  !providerQos.getSupportsOnChangeSubscriptions()) {
            continue;
        }
        if ( providerQos.getPriority() > highestPriority) {
            res = discoveryEntry.getParticipantId();
            LOG_TRACE(logger,"setting res to " + res);
            preferredConnection = selectPreferredCommunicationMiddleware(discoveryEntry.getConnections());
            highestPriority = providerQos.getPriority();
        }
    }
    if (res==""){
        LOG_WARN(logger,"There was more than one entries in capabilitiesEntries, but none had a Priority > 1");
        return;
    }

    updateArbitrationStatusParticipantIdAndAddress(ArbitrationStatus::ArbitrationSuccessful, res, preferredConnection);
}


} // namespace joynr
