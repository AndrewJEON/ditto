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
package org.eclipse.ditto.services.things.persistence.actors.strategies.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingLifecycle;
import org.eclipse.ditto.signals.events.things.ThingCreated;
import org.junit.Test;

/**
 * Unit test for {@link ThingCreatedStrategy}.
 */
public final class ThingCreatedStrategyTest extends AbstractStrategyTest {

    @Test
    public void assertImmutability() {
        assertInstancesOf(ThingCreatedStrategy.class, areImmutable());
    }

    @Test
    public void appliesEventCorrectly() {
        final ThingCreatedStrategy strategy = new ThingCreatedStrategy();
        final ThingCreated event = ThingCreated.of(THING, REVISION, DittoHeaders.empty());

        final Thing thingWithEventApplied = strategy.handle(event, null, NEXT_REVISION);

        final Thing expected = THING.toBuilder()
                .setLifecycle(ThingLifecycle.ACTIVE)
                .setRevision(NEXT_REVISION)
                .build();

        assertThat(thingWithEventApplied).isEqualTo(expected);
    }

}
