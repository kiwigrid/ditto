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

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.Source;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientActor;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientData;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientState;
import org.eclipse.ditto.services.connectivity.messaging.config.ConnectionConfig;
import org.eclipse.ditto.services.connectivity.messaging.config.MqttConfig;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientConnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientDisconnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.internal.ImmutableConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.mqtt.alpakka.MqttPublisherActor.RetrieveStatus;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.FSM;
import akka.actor.Props;
import akka.actor.Status;
import akka.japi.Pair;
import akka.japi.pf.FSMStateFunctionBuilder;
import akka.pattern.Patterns;
import akka.stream.ActorMaterializer;
import akka.stream.Graph;
import akka.stream.KillSwitches;
import akka.stream.Outlet;
import akka.stream.SharedKillSwitch;
import akka.stream.SinkShape;
import akka.stream.UniformFanOutShape;
import akka.stream.alpakka.mqtt.MqttMessage;
import akka.stream.javadsl.Balance;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.GraphDSL;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;

/**
 * Actor which handles connection to MQTT 3.1.1 server.
 */
public final class MqttClientActor extends BaseClientActor {

    private SharedKillSwitch consumerKillSwitch;

    private final Map<String, ActorRef> consumerByActorNameWithIndex;
    private final Set<ActorRef> pendingStatusReportsFromStreams;
    private final BiFunction<Connection, DittoHeaders, MqttConnectionFactory> connectionFactoryCreator;
    private final int sourceBufferSize;

    private CompletableFuture<Status.Status> testConnectionFuture = null;
    private CompletableFuture<Status.Status> startConsumersFuture = null;
    private CompletionStage<Done> allStreamsTerminatedFuture = null;
    private MqttConnectionFactory factory;
    private ActorRef mqttPublisherActor;

    @SuppressWarnings("unused")
    MqttClientActor(final Connection connection, final ActorRef conciergeForwarder,
            final BiFunction<Connection, DittoHeaders, MqttConnectionFactory> connectionFactoryCreator) {

        super(connection, conciergeForwarder);
        this.connectionFactoryCreator = connectionFactoryCreator;
        consumerByActorNameWithIndex = new HashMap<>();
        pendingStatusReportsFromStreams = new HashSet<>();

        final ConnectionConfig connectionConfig = connectivityConfig.getConnectionConfig();
        final MqttConfig mqttConfig = connectionConfig.getMqttConfig();
        sourceBufferSize = mqttConfig.getSourceBufferSize();
    }

    @SuppressWarnings("unused") // used by `props` via reflection
    private MqttClientActor(final Connection connection, final ActorRef conciergeForwarder) {

        this(connection, conciergeForwarder, MqttConnectionFactory::of);
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param connection the connection
     * @param conciergeForwarder the actor used to send signals to the concierge service.
     * @return the Akka configuration Props object.
     */
    public static Props props(final Connection connection, final ActorRef conciergeForwarder) {

        return Props.create(MqttClientActor.class, validateConnection(connection), conciergeForwarder);
    }

    private static Connection validateConnection(final Connection connection) {
        // nothing to do so far
        return connection;
    }

    @Override
    protected FSMStateFunctionBuilder<BaseClientState, BaseClientData> inTestingState() {
        return super.inTestingState()
                .event(Status.Status.class, (e, d) -> !Objects.equals(getSender(), getSelf()),
                        this::handleStatusReportFromChildren)
                .event(ClientConnected.class, BaseClientData.class, (event, data) -> {
                    final String url = data.getConnection().getUri();
                    final String message = "mqtt connection to " + url + " established successfully";

                    allocateResourcesOnConnection(event);

                    startPublisherActor()
                            .thenRun(() -> startConsumerActors(event))
                            .thenRun(() -> completeTestConnectionFuture(new Status.Success(message), data))
                            .exceptionally(t -> {
                                final ImmutableConnectionFailure connectionFailure =
                                        new ImmutableConnectionFailure(null, t, "test connection failed");
                                getSelf().tell(connectionFailure, ActorRef.noSender());
                                return null;
                            });

                    return stay();
                })
                .event(ConnectionFailure.class, BaseClientData.class, (event, data) -> {
                    completeTestConnectionFuture(new Status.Failure(event.getFailure().cause()), data);
                    return stay();
                });
    }

    @Override
    protected FSMStateFunctionBuilder<BaseClientState, BaseClientData> inConnectingState() {
        return super.inConnectingState()
                .event(Status.Status.class, this::handleStatusReportFromChildren);
    }

    @Override
    protected CompletionStage<Status.Status> doTestConnection(final Connection connection) {
        if (testConnectionFuture != null) {
            final Exception error = new IllegalStateException("test future exists");
            return CompletableFuture.completedFuture(new Status.Failure(error));
        }
        testConnectionFuture = new CompletableFuture<>();
        connectClient(connection);
        return testConnectionFuture;
    }

    @Override
    protected void allocateResourcesOnConnection(final ClientConnected clientConnected) {
        // nothing to do here; publisher and consumers started already.
    }

    @Override
    protected void doConnectClient(final Connection connection, @Nullable final ActorRef origin) {
        connectClient(connection);
    }

    @Override
    protected void doDisconnectClient(final Connection connection, @Nullable final ActorRef origin) {
        activateConsumerKillSwitch();
        if (allStreamsTerminatedFuture != null) {
            Patterns.pipe(
                    allStreamsTerminatedFuture.thenApply(done ->
                            (ClientDisconnected) () -> Optional.ofNullable(origin)),
                    getContext().dispatcher()
            ).to(getSelf());
        } else {
            self().tell((ClientDisconnected) () -> Optional.ofNullable(origin), origin);
        }
    }

    @Override
    protected ActorRef getPublisherActor() {
        return mqttPublisherActor;
    }

    /**
     * Start MQTT publisher and subscribers, expect "Status.Success" from each of them, then send "ClientConnected" to
     * self.
     *
     * @param connection connection of the publisher and subscribers.
     */
    private void connectClient(final Connection connection) {
        factory = connectionFactoryCreator.apply(connection, stateData().getLastSessionHeaders());
        getSelf().tell((ClientConnected) () -> null, ActorRef.noSender());
    }

    private ActorRef startMqttPublisher(final MqttConnectionFactory factory, final boolean dryRun) {
        log.info("Starting MQTT publisher actor.");
        // ensure no previous publisher stays in memory
        stopChildActor(mqttPublisherActor);
        return startChildActorConflictFree(MqttPublisherActor.ACTOR_NAME,
                MqttPublisherActor.props(connectionId(), getTargetsOrEmptyList(), factory, dryRun));
    }

    @Override
    protected CompletionStage<Status.Status> startPublisherActor() {
        mqttPublisherActor = startMqttPublisher(factory, isDryRun());
        return Patterns.ask(mqttPublisherActor, RetrieveStatus.RETRIEVE_STATUS, Duration.ofSeconds(1))
                .thenApply(o -> DONE);
    }

    @Override
    protected CompletionStage<Status.Status> startConsumerActors(final ClientConnected clientConnected) {
        // start consumers
        startConsumersFuture = new CompletableFuture<>();
        allStreamsTerminatedFuture = CompletableFuture.completedFuture(Done.done());
        if (isConsuming()) {
            // start new KillSwitch for the next batch of consumers
            refreshConsumerKillSwitch(KillSwitches.shared("consumerKillSwitch"));
            connection().getSources().forEach(source ->
                    allStreamsTerminatedFuture = allStreamsTerminatedFuture.thenCompose(done ->
                            startMqttConsumers(factory, getMessageMappingProcessorActor(), source, isDryRun())
                    )
            );
        } else {
            log.info("Not starting consumption because there is no source.");
            startConsumersFuture.complete(DONE);
        }
        return startConsumersFuture;
    }

    private CompletionStage<Done> startMqttConsumers(final MqttConnectionFactory factory,
            final ActorRef messageMappingProcessorActor,
            final Source source,
            final boolean dryRun) {

        CompletionStage<Done> allStreamsTerminatedFuture = CompletableFuture.completedFuture(Done.done());

        if (source.getConsumerCount() <= 0) {
            log.info("source #{} has {} consumer - not starting stream", source.getIndex(), source.getConsumerCount());
            return allStreamsTerminatedFuture;
        }

        for (int i = 0; i < source.getConsumerCount(); i++) {

            log.debug("Starting {}. consumer actor for source <{}> on connection <{}> with payload mapping {}.", i,
                    source.getIndex(),
                    factory.connectionId(), source.getPayloadMapping());

            final String uniqueSuffix = getUniqueSourceSuffix(source.getIndex(), i);
            final String actorNamePrefix = MqttConsumerActor.ACTOR_NAME_PREFIX + uniqueSuffix;

            final Props mqttConsumerActorProps =
                    MqttConsumerActor.props(connectionId(), messageMappingProcessorActor,
                            source.getAuthorizationContext(),
                            source.getEnforcement().orElse(null),
                            dryRun, String.join(";", source.getAddresses()),
                            source.getPayloadMapping());
            final ActorRef mqttConsumerActor = startChildActorConflictFree(actorNamePrefix, mqttConsumerActorProps);

            consumerByActorNameWithIndex.put(actorNamePrefix, mqttConsumerActor);
        }

        // failover implemented by factory
        final akka.stream.javadsl.Source<MqttMessage, CompletionStage<Done>> mqttStreamSource =
                factory.newSource(source, sourceBufferSize);

        final Graph<SinkShape<MqttMessage>, NotUsed> consumerLoadBalancer =
                createConsumerLoadBalancer(consumerByActorNameWithIndex.values());

        final Sink<MqttMessage, CompletionStage<Done>> messageSinkWithCompletionReport =
                Flow.fromSinkAndSourceCoupled(consumerLoadBalancer,
                        akka.stream.javadsl.Source.fromCompletionStage(new CompletableFuture<>()))
                        .toMat(Sink.ignore(), Keep.right());

        final Pair<CompletionStage<Done>, CompletionStage<Done>> futurePair =
                mqttStreamSource.viaMat(consumerKillSwitch.flow(), Keep.left())
                        .toMat(messageSinkWithCompletionReport, Keep.both())
                        .run(ActorMaterializer.create(getContext()));

        final CompletionStage<Done> subscriptionInitialized = futurePair.first();
        // append stream completion to the future
        allStreamsTerminatedFuture =
                allStreamsTerminatedFuture.thenCompose(done -> futurePair.second().exceptionally(error -> Done.done()));

        // consumerCount > 0 because the early return did not trigger
        final ActorRef firstConsumer = consumerByActorNameWithIndex.values().iterator().next();
        final ActorRef self = getSelf();
        pendingStatusReportsFromStreams.add(firstConsumer);
        subscriptionInitialized.handle((done, error) -> {
            final Collection<String> sourceAddresses = source.getAddresses();
            if (error == null) {
                connectionLogger.success("Subscriptions {0} initialized successfully.", sourceAddresses);
                log.info("Subscriptions {} initialized successfully", sourceAddresses);
                self.tell(new Status.Success(done), firstConsumer);
            } else {
                log.info("Subscriptions {} failed due to {}: {}", sourceAddresses,
                        error.getClass().getCanonicalName(), error.getMessage());
                self.tell(new ImmutableConnectionFailure(null, error, "subscriptions"), firstConsumer);
            }
            return done;
        });

        return allStreamsTerminatedFuture;
    }

    private static String getUniqueSourceSuffix(final int sourceIndex, final int consumerIndex) {
        return sourceIndex + "-" + consumerIndex;
    }

    @Override
    protected void cleanupResourcesForConnection() {
        pendingStatusReportsFromStreams.clear();
        activateConsumerKillSwitch();
        stopCommandConsumers();
        stopChildActor(mqttPublisherActor);
    }

    private void activateConsumerKillSwitch() {
        refreshConsumerKillSwitch(null);
    }

    private void refreshConsumerKillSwitch(@Nullable final SharedKillSwitch nextKillSwitch) {
        if (consumerKillSwitch != null) {
            log.info("Closing consumers stream.");
            consumerKillSwitch.shutdown();
        }
        consumerKillSwitch = nextKillSwitch;
    }

    private void stopCommandConsumers() {
        consumerByActorNameWithIndex.forEach((actorNamePrefix, child) -> stopChildActor(child));
        consumerByActorNameWithIndex.clear();
        allStreamsTerminatedFuture = null;
    }

    private FSM.State<BaseClientState, BaseClientData> handleStatusReportFromChildren(final Status.Status status,
            final BaseClientData data) {

        if (pendingStatusReportsFromStreams.contains(getSender())) {
            pendingStatusReportsFromStreams.remove(getSender());
            if (status instanceof Status.Failure) {
                final Status.Failure failure = (Status.Failure) status;
                startConsumersFuture.completeExceptionally(failure.cause());
            } else if (pendingStatusReportsFromStreams.isEmpty()) {
                // all children are ready; this client actor is connected.
                startConsumersFuture.complete(DONE);
            }
        }
        return stay();
    }

    private void completeTestConnectionFuture(final Status.Status testResult, final BaseClientData data) {
        if (testConnectionFuture != null) {
            testConnectionFuture.complete(testResult);
        } else {
            // no future; test failed.
            final Exception exception = new IllegalStateException("test future not found");
            getSelf().tell(new Status.Failure(exception), getSelf());
        }
    }

    private static <T> Graph<SinkShape<T>, NotUsed> createConsumerLoadBalancer(final Collection<ActorRef> routees) {
        return GraphDSL.create(builder -> {
            final UniformFanOutShape<T, T> loadBalancer = builder.add(Balance.create(routees.size()));
            int i = 0;
            for (final ActorRef routee : routees) {
                final Sink<Object, NotUsed> sink = Sink.actorRefWithAck(routee,
                        ConsumerStreamMessage.STREAM_STARTED,
                        ConsumerStreamMessage.STREAM_ACK,
                        ConsumerStreamMessage.STREAM_ENDED,
                        error -> ConsumerStreamMessage.STREAM_ENDED);
                final SinkShape<Object> sinkShape = builder.add(sink);
                final Outlet<T> outlet = loadBalancer.out(i++);
                builder.from(outlet).to(sinkShape);
            }
            return SinkShape.of(loadBalancer.in());
        });
    }

    enum ConsumerStreamMessage {

        STREAM_STARTED,

        STREAM_ACK,

        /**
         * Message for stream completion and stream failure.
         */
        STREAM_ENDED

    }

}
