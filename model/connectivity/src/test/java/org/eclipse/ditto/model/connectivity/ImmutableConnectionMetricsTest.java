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

package org.eclipse.ditto.model.connectivity;

import static org.mutabilitydetector.unittesting.AllowedReason.provided;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.junit.Test;

import nl.jqno.equalsverifier.EqualsVerifier;

/**
 * Unit test for {@link ImmutableConnectionMetrics}.
 */
public class ImmutableConnectionMetricsTest {

    @Test
    public void testHashCodeAndEquals() {
        EqualsVerifier.forClass(ImmutableConnectionMetrics.class)
                .verify();
    }

    @Test
    public void assertImmutability() {
        assertInstancesOf(ImmutableConnectionMetrics.class,
                areImmutable(),
                provided(AddressMetric.class).isAlsoImmutable());
    }
}
