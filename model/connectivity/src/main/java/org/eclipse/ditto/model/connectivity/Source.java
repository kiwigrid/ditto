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
package org.eclipse.ditto.model.connectivity;

import java.util.Optional;
import java.util.Set;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonFieldDefinition;
import org.eclipse.ditto.json.JsonFieldSelector;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.json.FieldType;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;
import org.eclipse.ditto.model.base.json.Jsonifiable;

/**
 * A {@link Connection} source contains several addresses to consume external messages from.
 */
public interface Source extends Jsonifiable.WithFieldSelectorAndPredicate<JsonField> {

    /**
     * @return the addresses of this source
     */
    Set<String> getAddresses();

    /**
     * @return number of consumers (connections) that will be opened to the remote server, default is {@code 1}
     */
    int getConsumerCount();

    /**
     * Returns the Authorization Context of this {@code Source}. If an authorization context is set on a {@link Source}
     * it overrides the authorization context set on the enclosing {@link Connection}.
     *
     * @return the Authorization Context of this {@link Source}.
     */
    AuthorizationContext getAuthorizationContext();

    /**
     * @return an index to distinguish between sources that would otherwise be different
     */
    int getIndex();

    /**
     * @return the optional qos value of this source - only applicable for certain {@link ConnectionType}s.
     */
    Optional<Integer> getQos();

    /**
     * @return the enforcement options that should be applied to this source
     */
    Optional<Enforcement> getEnforcement();

    /**
     * Defines an optional header mapping e.g. rename, combine etc. headers for inbound message. Mapping is
     * applied after payload mapping is applied. The mapping may contain {@code thing:*} and {@code header:*}
     * placeholders.
     *
     * @return the optional header mappings
     */
    Optional<HeaderMapping> getHeaderMapping();

    /**
     * The payload mappings that should be applied for messages received on this source. Each
     * mapping can produce multiple signals on its own that are then forwarded independently.
     *
     * @return the payload mappings to execute
     */
    PayloadMapping getPayloadMapping();

    /**
     * Returns all non hidden marked fields of this {@code Source}.
     *
     * @return a JSON object representation of this Source including only non hidden marked fields.
     */
    @Override
    default JsonObject toJson() {
        return toJson(FieldType.notHidden());
    }

    @Override
    default JsonObject toJson(final JsonSchemaVersion schemaVersion, final JsonFieldSelector fieldSelector) {
        return toJson(schemaVersion, FieldType.notHidden()).get(fieldSelector);
    }

    /**
     * An enumeration of the known {@code JsonField}s of a {@code Source} configuration.
     */
    @Immutable
    final class JsonFields {

        /**
         * JSON field containing the {@code JsonSchemaVersion}.
         */
        public static final JsonFieldDefinition<Integer> SCHEMA_VERSION =
                JsonFactory.newIntFieldDefinition(JsonSchemaVersion.getJsonKey(), FieldType.SPECIAL, FieldType.HIDDEN,
                        JsonSchemaVersion.V_1, JsonSchemaVersion.V_2);

        /**
         * JSON field containing the {@code Source} addresses.
         */
        public static final JsonFieldDefinition<JsonArray> ADDRESSES =
                JsonFactory.newJsonArrayFieldDefinition("addresses", FieldType.REGULAR, JsonSchemaVersion.V_1,
                        JsonSchemaVersion.V_2);

        /**
         * JSON field containing the {@code Source} consumer count.
         */
        public static final JsonFieldDefinition<Integer> CONSUMER_COUNT =
                JsonFactory.newIntFieldDefinition("consumerCount", FieldType.REGULAR, JsonSchemaVersion.V_1,
                        JsonSchemaVersion.V_2);

        /**
         * JSON field containing the {@code Source} qos.
         */
        public static final JsonFieldDefinition<Integer> QOS =
                JsonFactory.newIntFieldDefinition("qos", FieldType.REGULAR, JsonSchemaVersion.V_1,
                        JsonSchemaVersion.V_2);

        /**
         * JSON field containing the {@code Source} authorization context (list of authorization subjects).
         */
        public static final JsonFieldDefinition<JsonArray> AUTHORIZATION_CONTEXT =
                JsonFactory.newJsonArrayFieldDefinition("authorizationContext", FieldType.REGULAR,
                        JsonSchemaVersion.V_1, JsonSchemaVersion.V_2);

        /**
         * JSON field containing the {@code Source} enforcement options.
         */
        public static final JsonFieldDefinition<JsonObject> ENFORCEMENT =
                JsonFactory.newJsonObjectFieldDefinition("enforcement", FieldType.REGULAR,
                        JsonSchemaVersion.V_1, JsonSchemaVersion.V_2);

        /**
         * JSON field containing the {@code Source} header mapping.
         */
        public static final JsonFieldDefinition<JsonObject> HEADER_MAPPING =
                JsonFactory.newJsonObjectFieldDefinition("headerMapping", FieldType.REGULAR,
                        JsonSchemaVersion.V_1, JsonSchemaVersion.V_2);

        /**
         * JSON field containing the {@code Source} payload mapping.
         */
        public static final JsonFieldDefinition<JsonArray> PAYLOAD_MAPPING =
                JsonFactory.newJsonArrayFieldDefinition("payloadMapping", FieldType.REGULAR,
                        JsonSchemaVersion.V_1, JsonSchemaVersion.V_2);

        JsonFields() {
            throw new AssertionError();
        }

    }
}
