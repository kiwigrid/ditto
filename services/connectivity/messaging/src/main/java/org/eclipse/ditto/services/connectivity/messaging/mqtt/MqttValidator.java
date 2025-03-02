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
package org.eclipse.ditto.services.connectivity.messaging.mqtt;

import static org.eclipse.ditto.model.placeholders.PlaceholderFactory.newHeadersPlaceholder;
import static org.eclipse.ditto.model.placeholders.PlaceholderFactory.newThingPlaceholder;
import static org.eclipse.ditto.model.placeholders.PlaceholderFactory.newTopicPathPlaceholder;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionConfigurationInvalidException;
import org.eclipse.ditto.model.connectivity.ConnectionType;
import org.eclipse.ditto.model.connectivity.Enforcement;
import org.eclipse.ditto.model.connectivity.Source;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.model.placeholders.EnforcementFactoryFactory;
import org.eclipse.ditto.model.placeholders.PlaceholderFactory;
import org.eclipse.ditto.model.placeholders.PlaceholderFilter;
import org.eclipse.ditto.model.placeholders.SourceAddressPlaceholder;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.services.connectivity.messaging.validation.AbstractProtocolValidator;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttTopic;

import com.hivemq.client.mqtt.datatypes.MqttQos;

import akka.actor.ActorSystem;
import akka.stream.alpakka.mqtt.MqttQoS;

/**
 * Connection specification for Mqtt protocol.
 */
@Immutable
public final class MqttValidator extends AbstractProtocolValidator {

    private static final String INVALID_TOPIC_FORMAT = "The provided topic ''{0}'' is not valid: {1}";
    private static final String QOS = "qos";

    private static final Collection<String> ACCEPTED_SCHEMES =
            Collections.unmodifiableList(Arrays.asList("tcp", "ssl"));
    private static final Collection<String> SECURE_SCHEMES = Collections.unmodifiableList(
            Collections.singletonList("ssl"));

    private static final String ERROR_DESCRIPTION = "''{0}'' is not a valid value for mqtt enforcement. Valid" +
            " values are: ''{1}''.";

    /**
     * Create a new {@code MqttConnectionSpec}.
     *
     * @return a new instance.
     */
    public static MqttValidator newInstance() {
        return new MqttValidator();
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.MQTT;
    }

    @Override
    public void validate(final Connection connection, final DittoHeaders dittoHeaders, final ActorSystem actorSystem) {
        validateUriScheme(connection, dittoHeaders, ACCEPTED_SCHEMES, SECURE_SCHEMES, "MQTT 3.1.1");
        validateUriByPaho(connection, dittoHeaders);
        validateClientCount(connection, dittoHeaders);
        validateAddresses(connection, dittoHeaders);
        validateSourceConfigs(connection, dittoHeaders);
        validateTargetConfigs(connection, dittoHeaders);
        validatePayloadMappings(connection, actorSystem, dittoHeaders);
    }

    @Override
    protected void validateSource(final Source source, final DittoHeaders dittoHeaders,
            final Supplier<String> sourceDescription) {
        final Optional<Integer> qos = source.getQos();
        if (!(qos.isPresent())) {
            final String message =
                    MessageFormat.format("MQTT Source {0} must contain QoS value.", sourceDescription.get());
            throw ConnectionConfigurationInvalidException.newBuilder(message)
                    .dittoHeaders(dittoHeaders)
                    .build();
        }

        if (source.getHeaderMapping().isPresent()) {
            throw ConnectionConfigurationInvalidException.newBuilder("Header mapping is not supported for MQTT " +
                    "sources.").dittoHeaders(dittoHeaders).build();
        }

        validateSourceQoS(qos.get(), dittoHeaders, sourceDescription);
        validateSourceEnforcement(source.getEnforcement().orElse(null), dittoHeaders, sourceDescription);

        validateConsumerCount(source, dittoHeaders);
    }

    @Override
    protected void validateTarget(final Target target, final DittoHeaders dittoHeaders,
            final Supplier<String> targetDescription) {
        final Optional<Integer> qos = target.getQos();
        if (!(qos.isPresent())) {
            final String message =
                    MessageFormat.format("MQTT Target {0} must contain QoS value.", targetDescription.get());
            throw ConnectionConfigurationInvalidException.newBuilder(message)
                    .dittoHeaders(dittoHeaders)
                    .build();
        }

        if (target.getHeaderMapping().isPresent()) {
            throw ConnectionConfigurationInvalidException.newBuilder("Header mapping is not supported for MQTT " +
                    "targets.").dittoHeaders(dittoHeaders).build();
        }

        validateTargetQoS(qos.get(), dittoHeaders, targetDescription);
        validateTemplate(target.getAddress(), dittoHeaders, newThingPlaceholder(), newTopicPathPlaceholder(), newHeadersPlaceholder());
    }

    /**
     * Retrieve quality of service from a validated specific config with "at-most-once" as default.
     *
     * @param qos th configured qos value.
     * @return quality of service.
     */
    public static MqttQoS getQoS(final int qos) {
        switch (qos) {
            case 1:
                return MqttQoS.atLeastOnce();
            case 2:
                return MqttQoS.exactlyOnce();
            default:
                return MqttQoS.atMostOnce();
        }
    }

    /**
     * Retrieve quality of service from a validated specific config with "at-most-once" as default.
     *
     * @param qos th configured qos value.
     * @return quality of service.
     */
    public static MqttQos getHiveQoS(final int qos) {
        switch (qos) {
            case 1:
                return MqttQos.AT_LEAST_ONCE;
            case 2:
                return MqttQos.EXACTLY_ONCE;
            default:
                return MqttQos.AT_MOST_ONCE;
        }
    }

    private static void validateSourceEnforcement(@Nullable final Enforcement enforcement,
            final DittoHeaders dittoHeaders, final Supplier<String> sourceDescription) {
        if (enforcement != null) {

            validateEnforcementInput(enforcement, sourceDescription, dittoHeaders);

            final ThingId dummyThingId = ThingId.of("namespace","name");
            final Map<String, String> filtersMap = PlaceholderFilter.applyThingPlaceholderToAddresses(enforcement.getFilters(),
                    dummyThingId, filter -> {
                        throw invalidValueForConfig(filter, "filters", sourceDescription.get())
                                .description("Placeholder substitution failed. " +
                                        "Please check the placeholder variables against the documentation.")
                                .dittoHeaders(dittoHeaders)
                                .build();
                    });
            filtersMap.forEach((filter, mqttTopic) ->
                    validateMqttTopic(mqttTopic, true, errorMessage ->
                            invalidValueForConfig(filter, "filters", sourceDescription.get())
                                    .description(
                                            "The filter is not a valid MQTT topic after placeholder substitution. " +
                                                    "Wildcard characters are allowed.")
                                    .dittoHeaders(dittoHeaders)
                                    .build()));
        }
    }

    private static void validateEnforcementInput(final Enforcement enforcement,
            final Supplier<String> sourceDescription, final DittoHeaders dittoHeaders) {
        final SourceAddressPlaceholder sourceAddressPlaceholder = PlaceholderFactory.newSourceAddressPlaceholder();
        try {
            EnforcementFactoryFactory
                    .newEnforcementFilterFactory(enforcement, sourceAddressPlaceholder)
                    .getFilter("dummyTopic");
        } catch (final DittoRuntimeException e) {
            throw invalidValueForConfig(enforcement.getInput(), "input", sourceDescription.get())
                    .cause(e)
                    .description(MessageFormat.format(ERROR_DESCRIPTION, enforcement.getInput(),
                            sourceAddressPlaceholder.getSupportedNames()))
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    /*
     * MQTT Source does not support exactly-once delivery.
     */
    private static void validateSourceQoS(final int qos,
            final DittoHeaders dittoHeaders,
            final Supplier<String> errorSiteDescription) {

        validateQoS(qos, dittoHeaders, errorSiteDescription, i -> 0 <= i && i <= 2);
    }

    /*
     * MQTT Sink supports quality-of-service 0, 1, 2.
     */
    private static void validateTargetQoS(final int qos,
            final DittoHeaders dittoHeaders,
            final Supplier<String> errorSiteDescription) {

        validateQoS(qos, dittoHeaders, errorSiteDescription, i -> 0 <= i && i <= 2);
    }

    private static void validateQoS(final int qos, final DittoHeaders dittoHeaders,
            final Supplier<String> errorSiteDescription, final IntPredicate predicate) {

        if (!predicate.test(qos)) {
            throw invalidValueForConfig(qos, QOS, errorSiteDescription.get())
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    private static void validateAddresses(final Connection connection, final DittoHeaders dittoHeaders) {
        connection.getSources()
                .stream()
                .flatMap(source -> source.getAddresses().stream())
                .forEach(a -> validateAddress(a, true, dittoHeaders));
        // no wildcards allowed for publish targets
        connection.getTargets()
                .stream()
                .map(Target::getAddress)
                .forEach(a -> validateAddress(a, false, dittoHeaders));
    }

    private static void validateAddress(final String address, final boolean wildcardAllowed,
            final DittoHeaders dittoHeaders) {
        validateMqttTopic(address, wildcardAllowed, errorMessage -> {
            final String message = MessageFormat.format(INVALID_TOPIC_FORMAT, address, errorMessage);
            return ConnectionConfigurationInvalidException.newBuilder(message)
                    .dittoHeaders(dittoHeaders)
                    .build();
        });
    }

    private static void validateMqttTopic(final String address, final boolean wildcardAllowed,
            final Function<String, DittoRuntimeException> errorProducer) {
        try {
            MqttTopic.validate(address, wildcardAllowed);
        } catch (final IllegalArgumentException e) {
            throw errorProducer.apply(e.getMessage());
        }

    }

    private static void validateUriByPaho(final Connection connection, final DittoHeaders dittoHeaders) {
        try {
            MqttConnectOptions.validateURI(connection.getUri());
        } catch (final IllegalArgumentException e) {
            final String location = String.format("Connection with ID ''%s''", connection.getId());
            final String configName = Connection.JsonFields.URI.getPointer().toString();
            final String description =
                    "Hint: MQTT connection URI may not have trailing '/' or any other path component.";
            throw invalidValueForConfig(connection.getUri(), configName, location)
                    .description(description)
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    private void validateClientCount(final Connection connection,
            final DittoHeaders dittoHeaders) {
        if (connection.getClientCount() > 1) {
            throw ConnectionConfigurationInvalidException
                    .newBuilder("Client count limited to 1 for MQTT 3 connections.")
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    private void validateConsumerCount(final Source source,
            final DittoHeaders dittoHeaders) {
        if (source.getConsumerCount()>1) {
            throw ConnectionConfigurationInvalidException
                    .newBuilder("Consumer count limited to 1 for MQTT 3 connections.")
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    private static ConnectionConfigurationInvalidException.Builder invalidValueForConfig(final Object value,
            final String configName,
            final String location) {

        final String message = MessageFormat.format("Invalid value ''{0}'' for configuration ''{1}'' in {2}",
                value, configName, location);
        return ConnectionConfigurationInvalidException.newBuilder(message);
    }
}
