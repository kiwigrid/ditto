/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.messaging.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import javax.jms.CompletionListener;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;

import org.apache.qpid.jms.JmsSession;
import org.apache.qpid.jms.message.JmsMessage;
import org.apache.qpid.jms.message.JmsTextMessage;
import org.apache.qpid.jms.provider.amqp.AmqpConnection;
import org.apache.qpid.jms.provider.amqp.message.AmqpJmsTextMessageFacade;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.ConnectionId;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.services.connectivity.messaging.AbstractPublisherActorTest;
import org.eclipse.ditto.services.connectivity.messaging.TestConstants;
import org.eclipse.ditto.services.connectivity.messaging.amqp.status.ProducerClosedStatusReport;
import org.eclipse.ditto.services.connectivity.messaging.config.ConnectionConfig;
import org.eclipse.ditto.services.connectivity.messaging.config.DittoConnectivityConfig;
import org.eclipse.ditto.services.models.connectivity.ExternalMessage;
import org.eclipse.ditto.services.models.connectivity.ExternalMessageFactory;
import org.eclipse.ditto.services.models.connectivity.OutboundSignal;
import org.eclipse.ditto.services.models.connectivity.OutboundSignalFactory;
import org.eclipse.ditto.services.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.signals.base.Signal;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;

public class AmqpPublisherActorTest extends AbstractPublisherActorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AmqpPublisherActorTest.class);

    private JmsSession session;
    private MessageProducer messageProducer;

    @Override
    protected void setupMocks(final TestProbe probe) throws JMSException {
        session = mock(JmsSession.class);
        messageProducer = mock(MessageProducer.class);
        when(session.createProducer(any(Destination.class))).thenReturn(messageProducer);
        when(session.createTextMessage(anyString())).thenAnswer((Answer<JmsMessage>) invocation -> {
            final String argument = invocation.getArgument(0);
            final AmqpJmsTextMessageFacade facade = new AmqpJmsTextMessageFacade() {{
                connection = mock(AmqpConnection.class);
            }};
            final JmsTextMessage jmsTextMessage = new JmsTextMessage(facade);
            jmsTextMessage.setText(argument);
            return jmsTextMessage;
        });
    }

    @Test
    public void testRecoverPublisher() throws Exception {

        new TestKit(actorSystem) {{
            final TestProbe probe = new TestProbe(actorSystem);
            setupMocks(probe);

            final OutboundSignal outboundSignal = mock(OutboundSignal.class);
            final Signal source = mock(Signal.class);
            when(source.getEntityId()).thenReturn(TestConstants.Things.THING_ID);
            when(source.getDittoHeaders()).thenReturn(DittoHeaders.empty());
            when(outboundSignal.getSource()).thenReturn(source);
            final Target target = createTestTarget();
            when(outboundSignal.getTargets()).thenReturn(Collections.singletonList(decorateTarget(target)));

            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder().putHeader("device_id", "ditto:thing").build();
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(dittoHeaders).withText("payload").build();
            final OutboundSignal.WithExternalMessage mappedOutboundSignal =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, externalMessage);

            final Props props = AmqpPublisherActor.props(ConnectionId.generateRandom(),
                    Collections.singletonList(TestConstants.Targets.TWIN_TARGET.withAddress(getOutboundAddress())),
                    session,
                    loadConnectionConfig());
            final ActorRef publisherActor = actorSystem.actorOf(props);

            publisherActor.tell(mappedOutboundSignal, getRef());
            publisherActor.tell(mappedOutboundSignal, getRef());

            // producer is cached so created only once
            verify(session, timeout(1_000).times(1)).createProducer(any(Destination.class));
            verify(messageProducer, timeout(1000).times(2)).send(any(Message.class), any(CompletionListener.class));

            // check that backoff works properly, we expect 4 invocations after 10 seconds
            // - first invocation is the initial creation of the producer from above
            // - by default backoff starts with 1 second and doubles with each subsequent backoff
            // --> we expect backoff to trigger after 1 second, 3 seconds, 7 seconds (+ some delay)
            // --> 4 calls to createProducer in total after 10 seconds
            for (int i = 0; i < 3; i++) {
                // trigger closing of producer
                publisherActor.tell(ProducerClosedStatusReport.get(messageProducer), getRef());
                final int wantedNumberOfInvocations = i + 2;
                final long millis =
                        1_000 // initial backoff
                                * (long) (Math.pow(2, i)) // backoff doubles with each retry
                                + 500; // give the producer some time to recover
                LOGGER.info("Want {} invocations after {}ms.", wantedNumberOfInvocations, millis);
                verify(session, after(millis)
                        .times(wantedNumberOfInvocations)).createProducer(any(Destination.class));
            }
        }};
    }

    @Test
    public void producerClosedDuringBackOffIsIgnored() throws Exception {
        new TestKit(actorSystem) {{
            final TestProbe probe = new TestProbe(actorSystem);
            setupMocks(probe);

            final OutboundSignal outboundSignal = mock(OutboundSignal.class);
            final Signal source = mock(Signal.class);
            when(source.getEntityId()).thenReturn(TestConstants.Things.THING_ID);
            when(source.getDittoHeaders()).thenReturn(DittoHeaders.empty());
            when(outboundSignal.getSource()).thenReturn(source);
            final Target target = createTestTarget();
            when(outboundSignal.getTargets()).thenReturn(Collections.singletonList(decorateTarget(target)));

            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder().putHeader("device_id", "ditto:thing").build();
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(dittoHeaders).withText("payload").build();
            final OutboundSignal.WithExternalMessage mappedOutboundSignal =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, externalMessage);

            final Props props = AmqpPublisherActor.props(ConnectionId.generateRandom(),
                    Collections.singletonList(TestConstants.Targets.TWIN_TARGET.withAddress(getOutboundAddress())),
                    session,
                    loadConnectionConfig());
            final ActorRef publisherActor = actorSystem.actorOf(props);

            publisherActor.tell(mappedOutboundSignal, getRef());
            publisherActor.tell(mappedOutboundSignal, getRef());

            // producer is cached so created only once
            verify(session, timeout(1_000)).createProducer(any(Destination.class));

            // and trigger closing of producer multiple times
            publisherActor.tell(ProducerClosedStatusReport.get(messageProducer), getRef());
            publisherActor.tell(ProducerClosedStatusReport.get(messageProducer), getRef());
            publisherActor.tell(ProducerClosedStatusReport.get(messageProducer), getRef());

            // check that createProducer is called only twice in the next 10 seconds (once for the initial create, once for the backoff)
            verify(session, after(10_000).times(2)).createProducer(any(Destination.class));
        }};
    }

    @Override
    protected Props getPublisherActorProps() {
        return AmqpPublisherActor.props(ConnectionId.of("theConnection"), Collections.emptyList(), session,
                loadConnectionConfig());
    }

    @Override
    protected void verifyPublishedMessage() throws Exception {
        final ArgumentCaptor<JmsMessage> messageCaptor = ArgumentCaptor.forClass(JmsMessage.class);

        verify(messageProducer, timeout(1000)).send(messageCaptor.capture(), any(CompletionListener.class));

        final Message message = messageCaptor.getValue();
        assertThat(message).isNotNull();
        assertThat(message.getStringProperty("thing_id")).isEqualTo(TestConstants.Things.THING_ID.toString());
        assertThat(message.getStringProperty("suffixed_thing_id")).isEqualTo(
                TestConstants.Things.THING_ID + ".some.suffix");
        assertThat(message.getStringProperty("prefixed_thing_id")).isEqualTo(
                "some.prefix." + TestConstants.Things.THING_ID);
        assertThat(message.getStringProperty("eclipse")).isEqualTo("ditto");
        assertThat(message.getStringProperty("device_id"))
                .isEqualTo(TestConstants.Things.THING_ID.toString());
    }

    @Override
    protected Target decorateTarget(final Target target) {
        return target;
    }

    protected String getOutboundAddress() {
        return "outbound";
    }

    private static ConnectionConfig loadConnectionConfig() {
        return DittoConnectivityConfig.of(DefaultScopedConfig.dittoScoped(CONFIG)).getConnectionConfig();
    }
}
