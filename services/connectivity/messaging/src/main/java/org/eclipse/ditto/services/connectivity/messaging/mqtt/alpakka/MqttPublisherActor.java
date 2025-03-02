/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.connectivity.messaging.mqtt.alpakka;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.common.CharsetDeterminer;
import org.eclipse.ditto.model.connectivity.ConnectionId;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.services.connectivity.messaging.BasePublisherActor;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.ConnectionMonitor;
import org.eclipse.ditto.services.connectivity.messaging.mqtt.MqttPublishTarget;
import org.eclipse.ditto.services.connectivity.messaging.mqtt.MqttValidator;
import org.eclipse.ditto.services.models.connectivity.ExternalMessage;
import org.eclipse.ditto.services.models.connectivity.OutboundSignal;
import org.eclipse.ditto.services.utils.akka.LogUtil;

import akka.Done;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Status;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.Pair;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.ActorMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.alpakka.mqtt.MqttMessage;
import akka.stream.alpakka.mqtt.MqttQoS;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Source;
import akka.util.ByteString;

/**
 * Responsible for publishing {@link ExternalMessage}s into an MQTT broker.
 */
public final class MqttPublisherActor extends BasePublisherActor<MqttPublishTarget> {

    static final String ACTOR_NAME = "mqttPublisher";

    // for target the default is qos=0 because we have qos=0 all over the akka cluster
    private static final int DEFAULT_TARGET_QOS = 0;

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private final ActorRef sourceActor;

    private final boolean dryRun;
    private Status.Status initStatus;

    @SuppressWarnings("unused")
    private MqttPublisherActor(final ConnectionId connectionId, final List<Target> targets,
            final MqttConnectionFactory factory,
            final boolean dryRun) {
        super(connectionId, targets);
        this.dryRun = dryRun;

        final Pair<ActorRef, CompletionStage<Done>> materializedValues =
                Source.<MqttMessage>actorRef(100, OverflowStrategy.dropHead())
                        .toMat(factory.newSink(), Keep.both())
                        .run(ActorMaterializer.create(getContext()));

        materializedValues.second().handle(this::handleDone);

        sourceActor = materializedValues.first();

        log.info("Publisher ready");
    }

    /**
     * Creates Akka configuration object {@link Props} for this {@code RabbitMQPublisherActor}.
     *
     * @param connectionId the connectionId this publisher belongs to.
     * @param targets the targets to publish to.
     * @param factory the factory to create MqttConnections with.
     * @param dryRun whether this publisher is only created for a test or not.
     * @return the Akka configuration Props object.
     */
    static Props props(final ConnectionId connectionId, final List<Target> targets,
            final MqttConnectionFactory factory, final boolean dryRun) {

        return Props.create(MqttPublisherActor.class, connectionId, targets, factory, dryRun);
    }

    @Override
    protected void preEnhancement(final ReceiveBuilder receiveBuilder) {
        receiveBuilder
                .match(OutboundSignal.WithExternalMessage.class, this::isDryRun,
                        outbound -> log.info("Message dropped in dry run mode: {}", outbound))
                .match(RetrieveStatus.class, retrieve -> getSender().tell(null != initStatus ? initStatus :
                        new Status.Success(Done.getInstance()), getSelf()));
    }

    @Override
    protected void postEnhancement(final ReceiveBuilder receiveBuilder) {
        // noop
    }

    @Override
    protected MqttPublishTarget toPublishTarget(final String address) {
        return MqttPublishTarget.of(address);
    }

    @Override
    protected MqttPublishTarget toReplyTarget(final String replyToAddress) {
        return MqttPublishTarget.of(replyToAddress);
    }

    @Override
    protected void publishMessage(@Nullable final Target target, final MqttPublishTarget publishTarget,
            final ExternalMessage message,
            final ConnectionMonitor publishedMonitor) {

        final MqttQoS targetQoS;
        if (target == null) {
            targetQoS = MqttQoS.atMostOnce();
        } else {
            final int qos = target.getQos().orElse(DEFAULT_TARGET_QOS);
            targetQoS = MqttValidator.getQoS(qos);
        }
        publishMessage(publishTarget, targetQoS, message, publishedMonitor);
    }

    private void publishMessage(final MqttPublishTarget publishTarget, final MqttQoS qos, final ExternalMessage message,
            final ConnectionMonitor publishedMonitor) {

        final MqttMessage mqttMessage = mapExternalMessageToMqttMessage(publishTarget, qos, message);
        if (log.isDebugEnabled()) {
            log.debug("Publishing MQTT message to topic <{}>: {}", mqttMessage.topic(),
                    mqttMessage.payload().utf8String());
        }
        sourceActor.tell(mqttMessage, getSelf());
        publishedMonitor.success(message);
    }

    private boolean isDryRun(final Object message) {
        return dryRun;
    }

    private static MqttMessage mapExternalMessageToMqttMessage(final MqttPublishTarget mqttTarget, final MqttQoS qos,
            final ExternalMessage externalMessage) {

        final ByteString payload;
        if (externalMessage.isTextMessage()) {
            final Charset charset = externalMessage.findContentType()
                    .map(MqttPublisherActor::determineCharset)
                    .orElse(StandardCharsets.UTF_8);
            payload = externalMessage
                    .getTextPayload()
                    .map(text -> ByteString.fromString(text, charset))
                    .orElse(ByteString.empty());
        } else if (externalMessage.isBytesMessage()) {
            payload = externalMessage.getBytePayload()
                    .map(ByteString::fromByteBuffer)
                    .orElse(ByteString.empty());
        } else {
            payload = ByteString.empty();
        }
        return MqttMessage.create(mqttTarget.getTopic(), payload).withQos(qos);
    }

    private static Charset determineCharset(final CharSequence contentType) {
        return CharsetDeterminer.getInstance().apply(contentType);
    }

    /*
     * Called inside future - must be thread-safe.
     */
    @Nullable
    private Done handleDone(@Nullable final Done done, @Nullable final Throwable exception) {
        if (exception != null) {
            log.info("Publisher failed with {}: {}", exception.getClass().getSimpleName(), exception.getMessage());
            this.initStatus = new Status.Failure(exception);
        } else {
            log.info("Got <{}>, stream finished!", done);
        }
        return done;
    }

    @Override
    protected DiagnosticLoggingAdapter log() {
        return log;
    }

    enum RetrieveStatus {
        RETRIEVE_STATUS
    }
}
