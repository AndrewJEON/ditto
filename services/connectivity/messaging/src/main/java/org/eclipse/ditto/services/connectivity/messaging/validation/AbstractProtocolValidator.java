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
package org.eclipse.ditto.services.connectivity.messaging.validation;

import static java.util.stream.Collectors.joining;
import static org.eclipse.ditto.services.models.connectivity.placeholder.PlaceholderFactory.newHeadersPlaceholder;
import static org.eclipse.ditto.services.models.connectivity.placeholder.PlaceholderFactory.newThingPlaceholder;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionConfigurationInvalidException;
import org.eclipse.ditto.model.connectivity.ConnectionType;
import org.eclipse.ditto.model.connectivity.ConnectionUriInvalidException;
import org.eclipse.ditto.model.connectivity.HeaderMapping;
import org.eclipse.ditto.model.connectivity.Source;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.services.models.connectivity.placeholder.Placeholder;
import org.eclipse.ditto.services.models.connectivity.placeholder.PlaceholderFilter;

/**
 * Protocol-specific specification for {@link org.eclipse.ditto.model.connectivity.Connection} objects.
 */
public abstract class AbstractProtocolValidator {

    private static final String ENFORCEMENT_ERROR_MESSAGE = "The placeholder ''{0}'' could not be processed " +
            "successfully by ''{1}''";

    /**
     * Type of connection for which this spec applies.
     *
     * @return the connection type.
     */
    public abstract ConnectionType type();

    /**
     * Check a connection of the declared type for errors and throw them if any exists.
     *
     * @param connection the connection to check for errors.
     * @param dittoHeaders headers of the command that triggered the connection validation.
     * @throws DittoRuntimeException if the connection has errors.
     */
    public abstract void validate(final Connection connection, final DittoHeaders dittoHeaders);

    /**
     * Check whether the URI scheme of the connection belongs to an accepted scheme.
     *
     * @param connection the connection to check.
     * @param dittoHeaders headers of the command that triggered the connection validation.
     * @param acceptedSchemes valid URI schemes for the connection type.
     * @param protocolName protocol name of the connection type.
     * @throws DittoRuntimeException if the URI scheme is not accepted.
     */
    protected static void validateUriScheme(final Connection connection,
            final DittoHeaders dittoHeaders,
            final Collection<String> acceptedSchemes,
            final String protocolName) {

        if (!acceptedSchemes.contains(connection.getProtocol())) {
            final String message =
                    MessageFormat.format("The URI scheme ''{0}'' is not valid for {1}.", connection.getProtocol(),
                            protocolName);
            final String description =
                    MessageFormat.format("Accepted URI schemes are: {0}", String.join(", ", acceptedSchemes));
            throw ConnectionUriInvalidException.newBuilder(connection.getUri())
                    .message(message)
                    .description(description)
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    /**
     * Validate protocol-specific configurations of sources.
     *
     * @param connection the connection to check.
     * @param dittoHeaders headers of the command that triggered the connection validation.
     */
    protected void validateSourceConfigs(final Connection connection, final DittoHeaders dittoHeaders) {
        connection.getSources().forEach(source ->
                validateSource(source, dittoHeaders, sourceDescription(source, connection)));
    }

    /**
     * Validates the passed in {@code source} e.g. by validating its {@code enforcement} and {@code headerMapping}
     * for valid placeholder usage.
     *
     * @param source the source to validate
     * @param dittoHeaders the DittoHeaders to use in order for e.g. building DittoRuntimeExceptions
     * @param sourceDescription a descriptive text of the source
     * @throws ConnectionConfigurationInvalidException in case the Source configuration is invalid
     */
    protected abstract void validateSource(final Source source, final DittoHeaders dittoHeaders,
            final Supplier<String> sourceDescription);

    /**
     * Validate protocol-specific configurations of targets.
     *
     * @param connection the connection to check.
     * @param dittoHeaders headers of the command that triggered the connection validation.
     */
    protected void validateTargetConfigs(final Connection connection,
            final DittoHeaders dittoHeaders) {
        connection.getTargets().forEach(target -> validateTarget(target, dittoHeaders, targetDescription(target, connection)));
    }

    /**
     * Validates the passed in {@code headerMapping} by validating valid placeholder usage.
     *
     * @param headerMapping the headerMapping to validate
     * @param dittoHeaders the DittoHeaders to use in order for e.g. building DittoRuntimeExceptions
     * @throws ConnectionConfigurationInvalidException in case the HeaderMapping configuration is invalid
     */
    protected void validateHeaderMapping(final HeaderMapping headerMapping, final DittoHeaders dittoHeaders) {
        headerMapping.getMapping().forEach((key, value)
                -> validateTemplate(value, dittoHeaders, newHeadersPlaceholder(), newThingPlaceholder()));
    }

    /**
     * Validates the passed in {@code target} e.g. by validating its {@code address} and {@code headerMapping}
     * for valid placeholder usage.
     *
     * @param target the target to validate
     * @param dittoHeaders the DittoHeaders to use in order for e.g. building DittoRuntimeExceptions
     * @param targetDescription a descriptive text of the target
     * @throws ConnectionConfigurationInvalidException in case the Target configuration is invalid
     */
    protected abstract void validateTarget(final Target target, final DittoHeaders dittoHeaders,
            final Supplier<String> targetDescription);

    /**
     * Obtain a supplier of a description of a source of a connection.
     *
     * @param source the source.
     * @param connection the connection.
     * @return supplier of the description.
     */
    private static Supplier<String> sourceDescription(final Source source, final Connection connection) {
        return () -> MessageFormat.format("Source of index {0} of connection ''{1}''",
                source.getIndex(), connection.getId());
    }

    /**
     * Obtain a supplier of a description of a target of a connection.
     *
     * @param target the target.
     * @param connection the connection.
     * @return supplier of the description.
     */
    private static Supplier<String> targetDescription(final Target target, final Connection connection) {
        return () -> MessageFormat.format("Target of address ''{0}'' of connection ''{1}''",
                target.getAddress(), connection.getId());
    }

    /**
     * Validates that the passed {@code template} is both valid and that the placeholders in the passed {@code template}
     * are completely replaceable by the provided {@code placeholders}.
     *
     * @param template a string potentially containing placeholders to replace
     * @param headers the DittoHeaders to use in order for e.g. building DittoRuntimeExceptions
     * @param placeholders the {@link Placeholder}s to use for replacement
     * @throws ConnectionConfigurationInvalidException in case the template's placeholders could not completely be
     * resolved
     */
    protected void validateTemplate(final String template, final DittoHeaders headers,
            final Placeholder<?>... placeholders) {
        validateTemplate(template, false, headers, placeholders);
    }

    /**
     * Validates that the passed {@code template} is both valid and depending on the {@code allowUnresolved} boolean
     * that the placeholders in the passed {@code template} are completely replaceable by the provided
     * {@code placeholders}.
     *
     * @param template a string potentially containing placeholders to replace
     * @param allowUnresolved whether to allow if there could be placeholders in the template left unreplaced
     * @param headers the DittoHeaders to use in order for e.g. building DittoRuntimeExceptions
     * @param placeholders the {@link Placeholder}s to use for replacement
     * @throws ConnectionConfigurationInvalidException in case the template's placeholders could not completely be
     * resolved
     */
    protected void validateTemplate(final String template, final boolean allowUnresolved, final DittoHeaders headers,
            final Placeholder<?>... placeholders) {
        try {
            PlaceholderFilter.validate(template, allowUnresolved, placeholders);
        } catch (final DittoRuntimeException exception) {
            throw ConnectionConfigurationInvalidException
                    .newBuilder(MessageFormat.format(ENFORCEMENT_ERROR_MESSAGE, template,
                            Stream.of(placeholders).map(Placeholder::getPrefix).collect(joining(","))))
                    .cause(exception)
                    .dittoHeaders(headers)
                    .build();
        }
    }
}
