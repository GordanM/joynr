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
#include "joynr/PrivateCopyAssign.h"
#include <gtest/gtest.h>
#include <gmock/gmock.h>
#include "common/in-process/InProcessMessagingSkeleton.h"
#include "joynr/MessageRouter.h"
#include "joynr/MessagingStubFactory.h"
#include "joynr/ClusterControllerDirectories.h"
#include "joynr/JoynrMessage.h"
#include "joynr/Dispatcher.h"
#include "joynr/SubscriptionCallback.h"
#include "joynr/SubscriptionPublication.h"
#include "joynr/SubscriptionStop.h"
#include "joynr/JoynrMessageFactory.h"
#include "joynr/Request.h"
#include "joynr/Reply.h"
#include "joynr/InterfaceRegistrar.h"
#include "joynr/MetaTypeRegistrar.h"
#include "joynr/tests/testRequestInterpreter.h"
#include "tests/utils/MockObjects.h"
#include "joynr/QtOnChangeWithKeepAliveSubscriptionQos.h"
#include <QString>
#include <string>
#include "joynr/LibjoynrSettings.h"
#include "joynr/tests/testTypes_QtTestEnum.h"

#include "joynr/types/Localisation_QtGpsLocation.h"

using namespace ::testing;

using namespace joynr;

ACTION_P(ReleaseSemaphore,semaphore)
{
    semaphore->release(1);
}

/**
  * Is an integration test. Tests from Dispatcher -> SubscriptionListener and RequestCaller
  */
class SubscriptionTest : public ::testing::Test {
public:
    SubscriptionTest() :
        mockMessageRouter(new MockMessageRouter()),
        mockCallback(new MockCallback<types::Localisation::QtGpsLocation>()),
        mockRequestCaller(new MockTestRequestCaller()),
        mockReplyCaller(new MockReplyCaller<types::Localisation::QtGpsLocation>(
                [this](const RequestStatus& status, const types::Localisation::QtGpsLocation& location) {
                    mockCallback->onSuccess(location);
                },
                [] (const RequestStatus status){
                })),
        mockGpsLocationListener(new MockSubscriptionListenerOneType<types::Localisation::QtGpsLocation>()),
        mockTestEnumSubscriptionListener(new MockSubscriptionListenerOneType<tests::testTypes::QtTestEnum::Enum>()),
        gpsLocation1(1.1, 2.2, 3.3, types::Localisation::GpsFixEnum::MODE2D, 0.0, 0.0, 0.0, 0.0, 444, 444, 444),
        qos(2000),
        providerParticipantId("providerParticipantId"),
        proxyParticipantId("proxyParticipantId"),
        requestReplyId("requestReplyId"),
        messageFactory(),
        messageSender(mockMessageRouter),
        dispatcher(&messageSender),
        subscriptionManager(NULL),
        publicationManager(NULL)
    {
    }

    void SetUp(){
        QFile::remove(LibjoynrSettings::DEFAULT_SUBSCRIPTIONREQUEST_STORAGE_FILENAME()); //remove stored subscriptions
        subscriptionManager = new SubscriptionManager();
        publicationManager = new PublicationManager();
        dispatcher.registerPublicationManager(publicationManager);
        dispatcher.registerSubscriptionManager(subscriptionManager);
        InterfaceRegistrar::instance().registerRequestInterpreter<tests::testRequestInterpreter>(tests::ItestBase::INTERFACE_NAME());
        MetaTypeRegistrar::instance().registerMetaType<types::Localisation::QtGpsLocation>();
        MetaTypeRegistrar::instance().registerEnumMetaType<joynr::tests::testTypes::QtTestEnum>();
    }

    void TearDown(){

    }

protected:
    std::shared_ptr<MockMessageRouter> mockMessageRouter;
    std::shared_ptr<MockCallback<types::Localisation::QtGpsLocation> > mockCallback;

    std::shared_ptr<MockTestRequestCaller> mockRequestCaller;
    std::shared_ptr<MockReplyCaller<types::Localisation::QtGpsLocation> > mockReplyCaller;
    std::shared_ptr<MockSubscriptionListenerOneType<types::Localisation::QtGpsLocation> > mockGpsLocationListener;
    std::shared_ptr<MockSubscriptionListenerOneType<tests::testTypes::QtTestEnum::Enum> > mockTestEnumSubscriptionListener;

    types::Localisation::GpsLocation gpsLocation1;

    // create test data
    MessagingQos qos;
    std::string providerParticipantId;
    std::string proxyParticipantId;
    QString requestReplyId;

    JoynrMessageFactory messageFactory;
    JoynrMessageSender messageSender;
    Dispatcher dispatcher;
    SubscriptionManager * subscriptionManager;
    PublicationManager* publicationManager;
private:
    DISALLOW_COPY_AND_ASSIGN(SubscriptionTest);
};


/**
  * Trigger:    The dispatcher receives a SubscriptionRequest.
  * Expected:   The PublicationManager creates a PublisherRunnable and polls
  *             the MockCaller for the attribute.
  */
TEST_F(SubscriptionTest, receive_subscriptionRequestAndPollAttribute) {
    qRegisterMetaType<QtOnChangeWithKeepAliveSubscriptionQos>("QtOnChangeWithKeepAliveSubscriptionQos");
    qRegisterMetaType<SubscriptionRequest>("SubscriptionRequest");

    // Use a semaphore to count and wait on calls to the mockRequestCaller
    QSemaphore semaphore(0);
    EXPECT_CALL(*mockRequestCaller, getLocation(_,_))
            .WillRepeatedly(
                DoAll(
                    Invoke(mockRequestCaller.get(), &MockTestRequestCaller::invokeOnSuccessFct),
                    ReleaseSemaphore(&semaphore)));

    QString attributeName = "Location";
    auto subscriptionQos = std::shared_ptr<QtSubscriptionQos>(new QtOnChangeWithKeepAliveSubscriptionQos(
                80, // validity_ms
                100, // minInterval_ms
                200, // maxInterval_ms
                80 // alertInterval_ms
    ));
    QString subscriptionId = "SubscriptionID";
    SubscriptionRequest subscriptionRequest;
    subscriptionRequest.setSubscriptionId(subscriptionId);
    subscriptionRequest.setSubscribeToName(attributeName);
    subscriptionRequest.setQos(subscriptionQos);

    JoynrMessage msg = messageFactory.createSubscriptionRequest(
                QString::fromStdString(proxyParticipantId),
                QString::fromStdString(providerParticipantId),
                qos,
                subscriptionRequest);

    dispatcher.addRequestCaller(providerParticipantId, mockRequestCaller);
    dispatcher.receive(msg);

    // Wait for a call to be made to the mockRequestCaller
    ASSERT_TRUE(semaphore.tryAcquire(1,1000));
}


/**
  * Trigger:    The dispatcher receives a Publication.
  * Expected:   The SubscriptionManager retrieves the correct SubscriptionCallback and the
  *             Interpreter executes it correctly
  */
TEST_F(SubscriptionTest, receive_publication ) {

    qRegisterMetaType<SubscriptionPublication>("SubscriptionPublication");

    // getType is used by the ReplyInterpreterFactory to create an interpreter for the reply
    // so this has to match with the type being passed to the dispatcher in the reply
    ON_CALL(*mockReplyCaller, getType()).WillByDefault(Return(QString("QtGpsLocation")));

    // Use a semaphore to count and wait on calls to the mockGpsLocationListener
    QSemaphore semaphore(0);
    EXPECT_CALL(*mockGpsLocationListener, onReceive(A<const types::Localisation::QtGpsLocation&>()))
            .WillRepeatedly(ReleaseSemaphore(&semaphore));

    //register the subscription on the consumer side
    QString attributeName = "Location";
    auto subscriptionQos = std::shared_ptr<QtSubscriptionQos>(new QtOnChangeWithKeepAliveSubscriptionQos(
                80, // validity_ms
                100, // minInterval_ms
                200, // maxInterval_ms
                80 // alertInterval_ms
    ));

    SubscriptionRequest subscriptionRequest;
    //construct a reply containing a QtGpsLocation
    SubscriptionPublication subscriptionPublication;
    subscriptionPublication.setSubscriptionId(subscriptionRequest.getSubscriptionId());
    QList<QVariant> response;
    response.append(QVariant::fromValue(types::Localisation::QtGpsLocation::createQt(gpsLocation1)));
    subscriptionPublication.setResponse(response);

    std::shared_ptr<SubscriptionCallback<types::Localisation::QtGpsLocation>> subscriptionCallback(
            new SubscriptionCallback<types::Localisation::QtGpsLocation>(mockGpsLocationListener));


    // subscriptionRequest is an out param
    subscriptionManager->registerSubscription(
                attributeName,
                subscriptionCallback,
                subscriptionQos,
                subscriptionRequest);
    // incoming publication from the provider
    JoynrMessage msg = messageFactory.createSubscriptionPublication(
                QString::fromStdString(providerParticipantId),
                QString::fromStdString(proxyParticipantId),
                qos,
                subscriptionPublication);

    dispatcher.receive(msg);

    // Assert that only one subscription message is received by the subscription listener
    ASSERT_TRUE(semaphore.tryAcquire(1, 1000));
    ASSERT_FALSE(semaphore.tryAcquire(1, 250));
}

/**
  * Trigger:    The dispatcher receives an enum Publication.
  * Expected:   The SubscriptionManager retrieves the correct SubscriptionCallback and the
  *             Interpreter executes it correctly
  */
TEST_F(SubscriptionTest, receive_enumPublication ) {

    qRegisterMetaType<SubscriptionPublication>("SubscriptionPublication");

    // getType is used by the ReplyInterpreterFactory to create an interpreter for the reply
    // so this has to match with the type being passed to the dispatcher in the reply
    ON_CALL(*mockReplyCaller, getType()).WillByDefault(Return(QString("QtTestEnum")));

    // Use a semaphore to count and wait on calls to the mockTestEnumSubscriptionListener
    QSemaphore semaphore(0);
    EXPECT_CALL(*mockTestEnumSubscriptionListener, onReceive(A<const joynr::tests::testTypes::QtTestEnum::Enum&>()))
            .WillRepeatedly(ReleaseSemaphore(&semaphore));

    //register the subscription on the consumer side
    QString attributeName = "testEnum";
    auto subscriptionQos = std::shared_ptr<QtSubscriptionQos>(new QtOnChangeWithKeepAliveSubscriptionQos(
                80, // validity_ms
                100, // minInterval_ms
                200, // maxInterval_ms
                80 // alertInterval_ms
    ));

    SubscriptionRequest subscriptionRequest;
    //construct a reply containing a QtGpsLocation
    SubscriptionPublication subscriptionPublication;
    subscriptionPublication.setSubscriptionId(subscriptionRequest.getSubscriptionId());
    QList<QVariant> response;
    response.append(QVariant::fromValue(joynr::tests::testTypes::QtTestEnum::createQt(tests::testTypes::TestEnum::ZERO)));
    subscriptionPublication.setResponse(response);

    std::shared_ptr<SubscriptionCallback<joynr::tests::testTypes::QtTestEnum::Enum>> subscriptionCallback(
            new SubscriptionCallback<joynr::tests::testTypes::QtTestEnum::Enum>(mockTestEnumSubscriptionListener));


    // subscriptionRequest is an out param
    subscriptionManager->registerSubscription(
                attributeName,
                subscriptionCallback,
                subscriptionQos,
                subscriptionRequest);
    // incoming publication from the provider
    JoynrMessage msg = messageFactory.createSubscriptionPublication(
                QString::fromStdString(providerParticipantId),
                QString::fromStdString(proxyParticipantId),
                qos,
                subscriptionPublication);

    dispatcher.receive(msg);

    // Assert that only one subscription message is received by the subscription listener
    ASSERT_TRUE(semaphore.tryAcquire(1, 1000));
    ASSERT_FALSE(semaphore.tryAcquire(1, 250));
}

/**
  * Precondition: Dispatcher receives a SubscriptionRequest for a not(yet) existing Provider.
  * Trigger:    The provider is registered.
  * Expected:   The PublicationManager registers the provider and notifies the PublicationManager
  *             to restore waiting Subscriptions
  */
TEST_F(SubscriptionTest, receive_RestoresSubscription) {

    qRegisterMetaType<QtOnChangeWithKeepAliveSubscriptionQos>("QtOnChangeWithKeepAliveSubscriptionQos");
    qRegisterMetaType<SubscriptionRequest>("SubscriptionRequest");

    // Use a semaphore to count and wait on calls to the mockRequestCaller
    QSemaphore semaphore(0);
    EXPECT_CALL(
            *mockRequestCaller,
            getLocation(A<std::function<void(const types::Localisation::GpsLocation&)>>(),
                        A<std::function<void(const joynr::JoynrException&)>>())
    )
            .WillOnce(DoAll(
                    Invoke(mockRequestCaller.get(), &MockTestRequestCaller::invokeOnSuccessFct),
                    ReleaseSemaphore(&semaphore)
            ));
    QString attributeName = "Location";
    auto subscriptionQos = std::shared_ptr<QtSubscriptionQos>(new QtOnChangeWithKeepAliveSubscriptionQos(
                80, // validity_ms
                100, // minInterval_ms
                200, // maxInterval_ms
                80 // alertInterval_ms
    ));
    QString subscriptionId = "SubscriptionID";

    SubscriptionRequest subscriptionRequest;
    subscriptionRequest.setSubscriptionId(subscriptionId);
    subscriptionRequest.setSubscribeToName(attributeName);
    subscriptionRequest.setQos(subscriptionQos);

    JoynrMessage msg = messageFactory.createSubscriptionRequest(
                QString::fromStdString(proxyParticipantId),
                QString::fromStdString(providerParticipantId),
                qos,
                subscriptionRequest);
    // first received message with subscription request

    dispatcher.receive(msg);
    dispatcher.addRequestCaller(providerParticipantId, mockRequestCaller);
    ASSERT_TRUE(semaphore.tryAcquire(1,15000));
    //Try to acquire a semaphore for up to 5 seconds. Acquireing the semaphore will only work, if the mockRequestCaller has been called
    //and will be much faster than waiting for 500ms to make sure it has been called
}

/**
  * Precondition: A provider is registered and there is at least one subscription for it.
  * Trigger:    The request caller is removed from the dispatcher
  * Expected:   The PublicationManager stops all subscriptions for this provider
  */
TEST_F(SubscriptionTest, removeRequestCaller_stopsPublications) {
    qRegisterMetaType<QtOnChangeWithKeepAliveSubscriptionQos>("QtOnChangeWithKeepAliveSubscriptionQos");
    qRegisterMetaType<SubscriptionRequest>("SubscriptionRequest");
    qRegisterMetaType<SubscriptionStop>("SubscriptionStop");

    // Use a semaphore to count and wait on calls to the mockRequestCaller
    QSemaphore semaphore(0);
    EXPECT_CALL(*mockRequestCaller, getLocation(_,_))
            .WillRepeatedly(
                DoAll(
                    Invoke(mockRequestCaller.get(), &MockTestRequestCaller::invokeOnSuccessFct),
                    ReleaseSemaphore(&semaphore)));

    dispatcher.addRequestCaller(providerParticipantId, mockRequestCaller);
    QString attributeName = "Location";
    auto subscriptionQos = std::shared_ptr<QtSubscriptionQos>(new QtOnChangeWithKeepAliveSubscriptionQos(
                1200, // validity_ms
                10, // minInterval_ms
                100, // maxInterval_ms
                1100 // alertInterval_ms
    ));
    QString subscriptionId = "SubscriptionID";
    SubscriptionRequest subscriptionRequest;
    subscriptionRequest.setSubscriptionId(subscriptionId);
    subscriptionRequest.setSubscribeToName(attributeName);
    subscriptionRequest.setQos(subscriptionQos);

    JoynrMessage msg = messageFactory.createSubscriptionRequest(
                QString::fromStdString(proxyParticipantId),
                QString::fromStdString(providerParticipantId),
                qos,
                subscriptionRequest);
    // first received message with subscription request
    dispatcher.receive(msg);
    // wait for two requests from the subscription
    ASSERT_TRUE(semaphore.tryAcquire(2, 1000));
    // remove the request caller
    dispatcher.removeRequestCaller(providerParticipantId);
    // assert that less than 2 requests happen in the next 300 milliseconds
    ASSERT_FALSE(semaphore.tryAcquire(2, 300));
}

/**
  * Precondition: A provider is registered and there is at least one subscription for it.
  * Trigger:    A subscription stop message is received
  * Expected:   The PublicationManager stops the publications for this provider
  */
TEST_F(SubscriptionTest, stopMessage_stopsPublications) {

    qRegisterMetaType<QtOnChangeWithKeepAliveSubscriptionQos>("QtOnChangeWithKeepAliveSubscriptionQos");
    qRegisterMetaType<SubscriptionRequest>("SubscriptionRequest");

    // Use a semaphore to count and wait on calls to the mockRequestCaller
    QSemaphore semaphore(0);
    EXPECT_CALL(*mockRequestCaller, getLocation(_,_))
            .WillRepeatedly(
                DoAll(
                    Invoke(mockRequestCaller.get(), &MockTestRequestCaller::invokeOnSuccessFct),
                    ReleaseSemaphore(&semaphore)));

    dispatcher.addRequestCaller(providerParticipantId, mockRequestCaller);
    QString attributeName = "Location";
    auto subscriptionQos = std::shared_ptr<QtSubscriptionQos>(new QtOnChangeWithKeepAliveSubscriptionQos(
                1200, // validity_ms
                10, // minInterval_ms
                100, // maxInterval_ms
                1100 // alertInterval_ms
    ));
    QString subscriptionId = "SubscriptionID";
    SubscriptionRequest subscriptionRequest;
    subscriptionRequest.setSubscriptionId(subscriptionId);
    subscriptionRequest.setSubscribeToName(attributeName);
    subscriptionRequest.setQos(subscriptionQos);

    JoynrMessage msg = messageFactory.createSubscriptionRequest(
                QString::fromStdString(proxyParticipantId),
                QString::fromStdString(providerParticipantId),
                qos,
                subscriptionRequest);
    // first received message with subscription request
    dispatcher.receive(msg);

    // wait for two requests from the subscription
    ASSERT_TRUE(semaphore.tryAcquire(2, 1000));

    SubscriptionStop subscriptionStop;
    subscriptionStop.setSubscriptionId(subscriptionRequest.getSubscriptionId());
    // receive a subscription stop message
    msg = messageFactory.createSubscriptionStop(
                QString::fromStdString(proxyParticipantId),
                QString::fromStdString(providerParticipantId),
                qos,
                subscriptionStop);
    dispatcher.receive(msg);

    // assert that less than 2 requests happen in the next 300 milliseconds
    ASSERT_FALSE(semaphore.tryAcquire(2, 300));
}


