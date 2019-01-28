/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.messaging.rabbitmq;

import static org.eclipse.ditto.services.models.connectivity.placeholder.PlaceholderFactory.newThingPlaceholder;
import static org.eclipse.ditto.services.models.connectivity.placeholder.PlaceholderFactory.newTopicPathPlaceholder;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionType;
import org.eclipse.ditto.model.connectivity.Source;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.services.connectivity.messaging.validation.AbstractProtocolValidator;
import org.eclipse.ditto.services.models.connectivity.placeholder.PlaceholderFactory;

/**
 * Connection specification for RabbitMQ protocol.
 */
@Immutable
public final class RabbitMQValidator extends AbstractProtocolValidator {

    private static final Collection<String> ACCEPTED_SCHEMES =
            Collections.unmodifiableList(Arrays.asList("amqp", "amqps"));

    /**
     * Create a new {@code RabbitMQConnectionSpec}.
     *
     * @return a new instance.
     */
    public static RabbitMQValidator newInstance() {
        return new RabbitMQValidator();
    }

    @Override
    protected void validateSource(final Source source, final DittoHeaders dittoHeaders,
            final Supplier<String> sourceDescription) {

        source.getEnforcement().ifPresent(enforcement -> {
            validateTemplate(enforcement.getInput(), dittoHeaders, PlaceholderFactory.newHeadersPlaceholder());
            enforcement.getFilters().forEach(filterTemplate ->
                    validateTemplate(filterTemplate, dittoHeaders, PlaceholderFactory.newThingPlaceholder()));
        });
        source.getHeaderMapping().ifPresent(mapping -> validateHeaderMapping(mapping, dittoHeaders));
    }

    @Override
    protected void validateTarget(final Target target, final DittoHeaders dittoHeaders,
            final Supplier<String> sourceDescription) {
        target.getHeaderMapping().ifPresent(mapping -> validateHeaderMapping(mapping, dittoHeaders));
        validateTemplate(target.getAddress(), dittoHeaders, newThingPlaceholder(), newTopicPathPlaceholder());
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.AMQP_091;
    }

    @Override
    public void validate(final Connection connection, final DittoHeaders dittoHeaders) {
        AbstractProtocolValidator.validateUriScheme(connection, dittoHeaders, ACCEPTED_SCHEMES, "AMQP 0.9.1");
    }
}
