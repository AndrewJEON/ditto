/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.things.persistence.snapshotting;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.services.models.things.commands.sudo.TakeSnapshot;
import org.eclipse.ditto.services.models.things.commands.sudo.TakeSnapshotResponse;
import org.eclipse.ditto.services.things.persistence.actors.AbstractReceiveStrategy;
import org.eclipse.ditto.services.things.persistence.actors.ReceiveStrategy;
import org.eclipse.ditto.services.things.persistence.actors.ThingPersistenceActor;
import org.eclipse.ditto.services.things.persistence.actors.ThingPersistenceActorInterface;
import org.eclipse.ditto.services.things.persistence.serializer.things.SnapshotTag;
import org.eclipse.ditto.services.things.persistence.serializer.things.TaggedThingJsonSnapshotAdapter;
import org.eclipse.ditto.services.things.persistence.serializer.things.ThingWithSnapshotTag;
import org.eclipse.ditto.services.utils.akka.persistence.SnapshotAdapter;
import org.eclipse.ditto.signals.commands.things.exceptions.ThingUnavailableException;

import com.mongodb.annotations.NotThreadSafe;

import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.event.DiagnosticLoggingAdapter;
import akka.pattern.PatternsCS;
import akka.persistence.DeleteMessagesFailure;
import akka.persistence.DeleteMessagesSuccess;
import akka.persistence.DeleteSnapshotFailure;
import akka.persistence.DeleteSnapshotSuccess;
import akka.persistence.Persistence;
import akka.persistence.SaveSnapshotFailure;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotMetadata;
import akka.persistence.SnapshotOffer;
import akka.persistence.SnapshotProtocol;
import akka.persistence.SnapshotSelectionCriteria;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

/**
 * An aspect of {@link ThingPersistenceActor} that deals with all tasks related to snapshots.
 * <ol>
 * <li>Handles {@code TakeSnapshot} commands.</li>
 * <li>Schedules regular snapshotting that can also be triggered from inside the {@code ThingPersistenceActor}.</li>
 * <li>Deletes redundant snapshots and events.</li>
 * <li>Handles responses from the snapshot store and the event journal.</li>
 * </ol>
 */
public class ThingSnapshotter {

    // Messages to send to self.
    private static final class TakeSnapshotInternal {}

    private static final class SaveSnapshotTimeout {

        final long sequenceNr;

        private SaveSnapshotTimeout(final long sequenceNr) {
            this.sequenceNr = sequenceNr;
        }
    }

    private SnapshotterState snapshotterState;
    private SnapshotterState lastSaneSnapshotterState;

    private final ThingPersistenceActorInterface persistentActor;
    @Nullable private final DiagnosticLoggingAdapter log;
    @Nullable private final FiniteDuration snapshotInterval;
    private final boolean snapshotDeleteOld;
    private final boolean eventsDeleteOld;

    private final SnapshotAdapter<ThingWithSnapshotTag> taggedSnapshotAdapter;
    @Nullable private final ActorRef snapshotPlugin;

    @Nullable private Cancellable scheduledMaintenanceSnapshot;
    @Nullable private Cancellable scheduledSnapshotTimeout;
    private boolean shouldTakeMaintenanceSnapshot;

    @Nullable private final FiniteDuration saveSnapshotTimeout;
    @Nullable private final FiniteDuration loadSnapshotTimeout;

    /**
     * Internal constructor; use {@link ThingSnapshotter#getInstance(ThingPersistenceActor, DiagnosticLoggingAdapter,
     * java.time.Duration, boolean, boolean)} instead.
     *
     * @param persistentActor The {@code ThingPersistenceActor} to whom this snapshotter belongs. Must not be null.
     * @param log The logger. If null, nothing is logged.
     * @param taggedSnapshotAdapter Serializer and deserializer of snapshots. Must not be null.
     * @param snapshotPlugin The actor from whom old snapshots can be retrieved. If null, no snapshot is retrieved.
     * @param snapshotDeleteOld Whether old and unprotected snapshots should be deleted.
     * @param eventsDeleteOld Whether events before a successfully saved snapshot should be deleted.
     * @param snapshotInterval How long to wait between scheduled maintenance snapshots.
     * @param saveSnapshotTimeout How long to wait for the snapshot store before giving up.
     * @param loadSnapshotTimeout How long to wait for {@code snapshotPlugin} before giving up.
     */
    ThingSnapshotter(final ThingPersistenceActorInterface persistentActor, final DiagnosticLoggingAdapter log,
            final SnapshotAdapter<ThingWithSnapshotTag> taggedSnapshotAdapter, final ActorRef snapshotPlugin,
            final boolean snapshotDeleteOld, final boolean eventsDeleteOld,
            final FiniteDuration snapshotInterval, final FiniteDuration saveSnapshotTimeout,
            final FiniteDuration loadSnapshotTimeout) {

        this.saveSnapshotTimeout = saveSnapshotTimeout;
        this.loadSnapshotTimeout = loadSnapshotTimeout;

        snapshotterState = new SnapshotterState();
        lastSaneSnapshotterState = new SnapshotterState();

        this.persistentActor = persistentActor;
        this.log = log;
        this.snapshotInterval = snapshotInterval;
        this.snapshotDeleteOld = snapshotDeleteOld;
        this.eventsDeleteOld = eventsDeleteOld;

        this.taggedSnapshotAdapter = taggedSnapshotAdapter;
        this.snapshotPlugin = snapshotPlugin;

        scheduledMaintenanceSnapshot = null;
        scheduledSnapshotTimeout = null;
        shouldTakeMaintenanceSnapshot = false;
    }

    /**
     * Creates a {@code ThingSnapshotter} for a {@code ThingPersistenceActor}.
     *
     * @param thingPersistenceActor The actor in which this snapshotter is run. Must not be null.
     * @param log The actor's logger. If null, nothing is logged.
     * @param snapshotDeleteOld Whether old and unprotected snapshots are to be deleted.
     * @param eventsDeleteOld Whether events before a saved snapshot are to be deleted.
     */
    public static ThingSnapshotter getInstance(final ThingPersistenceActor thingPersistenceActor,
            @Nullable final DiagnosticLoggingAdapter log, @Nullable final java.time.Duration snapshotInterval,
            final boolean snapshotDeleteOld, final boolean eventsDeleteOld) {
        final FiniteDuration finiteSnapshotInterval = snapshotInterval != null
                ? Duration.fromNanos(snapshotInterval.toNanos())
                : null;
        final SnapshotAdapter<ThingWithSnapshotTag> taggedSnapshotAdapter =
                new TaggedThingJsonSnapshotAdapter(thingPersistenceActor.getContext().system());
        final ActorRef snapshotPlugin = Persistence.get(thingPersistenceActor.getContext().system())
                .snapshotStoreFor(thingPersistenceActor.snapshotPluginId());
        final FiniteDuration saveSnapshotTimeout = Duration.create(500, TimeUnit.MILLISECONDS);
        final FiniteDuration loadSnapshotTimeout = Duration.create(3000, TimeUnit.MILLISECONDS);
        return new ThingSnapshotter(thingPersistenceActor, log, taggedSnapshotAdapter, snapshotPlugin,
                snapshotDeleteOld, eventsDeleteOld, finiteSnapshotInterval, saveSnapshotTimeout, loadSnapshotTimeout);
    }

    /**
     * Schedules a snapshot such that no reply is given after it is done.
     */
    public void takeSnapshotInternal() {
        persistentActor.self().tell(new TakeSnapshotInternal(), null);
    }

    /**
     * Starts to take maintenance snapshots every once in a while.
     */
    public void startMaintenanceSnapshots() {
        shouldTakeMaintenanceSnapshot = true;
        resetMaintenanceSnapshotSchedule();
    }

    /**
     * Stop taking maintenance snapshots.
     */
    public void stopMaintenanceSnapshots() {
        shouldTakeMaintenanceSnapshot = false;
    }

    /**
     * What to do if the {@link ThingPersistenceActor} is stopped.
     */
    public void postStop() {
        if (scheduledMaintenanceSnapshot != null) {
            scheduledMaintenanceSnapshot.cancel();
        }
        if (scheduledSnapshotTimeout != null) {
            scheduledSnapshotTimeout.cancel();
        }
    }

    /**
     * @return The sequence number of the latest ongoing snapshot or the last saved snapshot.
     */
    public long getLatestSnapshotSequenceNr() {
        return snapshotterState.getSequenceNr();
    }

    /**
     * @return True if the last snapshot is up to date and no snapshotting is ongoing, false otherwise.
     */
    public boolean lastSnapshotCompletedAndUpToDate() {
        return !snapshotterState.isInProgress() &&
                snapshotterState.getSequenceNr() == persistentActor.lastSequenceNr();
    }

    /**
     * Requests a snapshot of a specific revision number.
     *
     * @param snapshotRevision The revision number of the snapshot.
     * @return The Thing recovered from the snapshot if it exists and no errors happens while communicating with the
     * snapshot plugin (i. e., the actor from whom old snapshots can be retrieved).
     */
    public CompletionStage<Optional<Thing>> loadSnapshot(final long snapshotRevision) {

        if (snapshotPlugin != null && loadSnapshotTimeout != null) {

            final SnapshotProtocol.LoadSnapshot loadSnapshot = new SnapshotProtocol.LoadSnapshot(
                    persistentActor.persistenceId(),
                    SnapshotSelectionCriteria.create(snapshotRevision, Long.MAX_VALUE),
                    snapshotRevision);

            final CompletionStage<Optional<Thing>> futureThing =
                    PatternsCS.ask(snapshotPlugin, loadSnapshot, loadSnapshotTimeout.toMillis())
                            .thenApply(response -> {
                                if (response instanceof SnapshotProtocol.LoadSnapshotResult) {
                                    final SnapshotProtocol.LoadSnapshotResult result =
                                            (SnapshotProtocol.LoadSnapshotResult) response;
                                    if (result.snapshot().isDefined()) {
                                        return Optional.ofNullable(
                                                taggedSnapshotAdapter.fromSnapshotStore(result.snapshot().get()));
                                    }
                                }
                                return Optional.empty();
                            });
            return futureThing.exceptionally(error -> Optional.empty());
        } else {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * Recovers the passed in {@link SnapshotOffer} to a {@link Thing}.
     *
     * @param snapshotOffer the snapshot offer
     * @return the Thing restored from the snapshot offer or {@code null}.
     * @throws NullPointerException if {@code snapshotOffer} is {@code null}.
     */
    @Nullable
    public Thing recoverThingFromSnapshotOffer(@Nonnull final SnapshotOffer snapshotOffer) {
        checkNotNull(snapshotOffer, "snapshot offer");
        final ThingWithSnapshotTag result = taggedSnapshotAdapter.fromSnapshotStore(snapshotOffer);
        final SnapshotMetadata metadata = snapshotOffer.metadata();
        final SnapshotTag snapshotTag = Optional.ofNullable(result)
                .map(ThingWithSnapshotTag::getSnapshotTag)
                .orElse(SnapshotTag.UNPROTECTED);
        snapshotterState = new SnapshotterState(false, metadata.sequenceNr(), snapshotTag, null, null);
        return result;
    }

    /**
     * Strategies related to snapshotting. A {@link ThingPersistenceActor} activates snapshotting functions by
     * including these strategies in its receive function.
     *
     * @return The strategies.
     */
    @Nonnull
    public Collection<ReceiveStrategy<?>> strategies() {
        return Arrays.asList(
                new TakeSnapshotExternalStrategy(),
                new TakeSnapshotInternalStrategy(),
                new SaveSnapshotSuccessStrategy(),
                new SaveSnapshotFailureStrategy(),
                new SaveSnapshotTimeoutStrategy(),
                new DeleteSnapshotSuccessStrategy(),
                new DeleteSnapshotFailureStrategy(),
                new DeleteMessagesSuccessStrategy(),
                new DeleteMessagesFailureStrategy());
    }

    // Bookkeeping after saving a snapshot in the snapshot store. Timeout message is scheduled.
    private void saveSnapshotStarted(final long snapshotSequenceNr, final SnapshotTag snapshotTag,
            final ActorRef sender, final DittoHeaders dittoHeaders) {
        snapshotterState = new SnapshotterState(true, snapshotSequenceNr, snapshotTag, sender, dittoHeaders);
        scheduleSaveSnapshotTimeout(snapshotSequenceNr, sender);
    }

    // Bookkeeping after snapshotting failed. Timeout message is cancelled and pending TakeSnapshot commands are
    // unstashed. Maintenance snapshot schedule is reset.
    private void saveSnapshotFailed() {
        snapshotterState = lastSaneSnapshotterState;
        persistentActor.unstashAll();
        cancelSaveSnapshotTimeout();
        resetMaintenanceSnapshotSchedule();
    }

    // Bookkeeping after snapshotting succeeded. Timeout message is cancelled and pending TakeSnapshot commands are
    // unstashed. Maintenance snapshot schedule is reset.
    private void saveSnapshotSucceeded() {
        snapshotterState = new SnapshotterState(false, snapshotterState.getSequenceNr(),
                snapshotterState.getSnapshotTag(), null, null);
        lastSaneSnapshotterState = snapshotterState;
        persistentActor.unstashAll();
        cancelSaveSnapshotTimeout();
        resetMaintenanceSnapshotSchedule();
    }

    // Access to the logger; does nothing if the logger is null (e. g., in unit tests where this objects exists
    // outside of any actor system).
    private void doLog(final Consumer<DiagnosticLoggingAdapter> whatToLog) {
        if (log != null) {
            whatToLog.accept(log);
        }
    }

    /**
     * After the decision to save a snapshot is made, call this method to actually start the snapshotting process.
     *
     * @param snapshotTag Whether the snapshot is protected.
     * @param sender Who to reply to after snapshotting is done. If null, there will be no reply.
     * @param dittoHeaders Command headers for the response if snapshotting succeeds.
     */
    void doSaveSnapshot(final SnapshotTag snapshotTag, @Nullable final ActorRef sender,
            @Nullable DittoHeaders dittoHeaders) {
        checkNotNull(snapshotTag, "snapshot tag");

        final Thing thing = persistentActor.getThing();
        final long snapshotSequenceNr = persistentActor.snapshotSequenceNr();

        doLog(logger -> logger.debug("Attempting to take snapshot for Thing with ID <{}> and seqNr <{}>",
                persistentActor.getThingId(), snapshotSequenceNr));

        final ThingWithSnapshotTag thingWithSnapshotTag = ThingWithSnapshotTag.newInstance(thing, snapshotTag);
        final Object snapshotSubject = taggedSnapshotAdapter.toSnapshotStore(thingWithSnapshotTag);
        persistentActor.saveSnapshot(snapshotSubject);

        saveSnapshotStarted(snapshotSequenceNr, snapshotTag, sender, dittoHeaders);
    }

    /**
     * Decides whether an incoming response from the snapshot store arrived out of order.
     *
     * @param newSnapshotSequenceNr Sequence number of the incoming response.
     * @return Whether the response is out of order. If it is, then the snapshot is considered a failure and is deleted.
     */
    private boolean saveSnapshotResponseArrivedOutOfOrder(final long newSnapshotSequenceNr) {
        return !snapshotterState.isInProgress() || snapshotterState.getSequenceNr() != newSnapshotSequenceNr;
    }

    private boolean noChangeSinceOngoingSnapshot() {
        return persistentActor.lastSequenceNr() == snapshotterState.getSequenceNr();
    }

    private boolean canReuseProtectedSnapshot() {
        return noChangeSinceOngoingSnapshot() && snapshotterState.isProtected();
    }

    private void scheduleSaveSnapshotTimeout(final long sequenceNr, final ActorRef sender) {
        if (saveSnapshotTimeout != null) {
            scheduledSnapshotTimeout = scheduleOnce(saveSnapshotTimeout, new SaveSnapshotTimeout(sequenceNr), sender);
        }
    }

    private void cancelSaveSnapshotTimeout() {
        if (scheduledSnapshotTimeout != null) {
            scheduledSnapshotTimeout.cancel();
            scheduledSnapshotTimeout = null;
        }
    }

    private void resetMaintenanceSnapshotSchedule() {
        // if there is a previous snapshotter, cancel it
        if (scheduledMaintenanceSnapshot != null) {
            scheduledMaintenanceSnapshot.cancel();
        }
        if (shouldTakeMaintenanceSnapshot && snapshotInterval != null) {
            doLog(logger -> logger.debug("Scheduling for taking Snapshot if modified in <{}> (ISO-8601 duration)",
                    snapshotInterval));
            // send a message to ourselves:
            scheduledMaintenanceSnapshot = scheduleOnce(snapshotInterval, new TakeSnapshotInternal(), null);
        } else {
            doLog(logger -> logger.debug("Maintenance snapshotting is cancelled. No new maintenance snapshot is " +
                    "scheduled."));
        }
    }

    private Cancellable scheduleOnce(final FiniteDuration when, final Object message, final ActorRef sender) {
        return persistentActor.context()
                .system()
                .scheduler()
                .scheduleOnce(when, persistentActor.self(), message, persistentActor.context().dispatcher(),
                        sender);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "snapshotterState=" + snapshotterState +
                ", lastSaneSnapshotterState=" + lastSaneSnapshotterState +
                "]";
    }

    /**
     * This strategy handles external requests for taking a snapshot.
     */
    @NotThreadSafe
    private final class TakeSnapshotExternalStrategy extends AbstractReceiveStrategy<TakeSnapshot> {

        private TakeSnapshotExternalStrategy() {
            super(TakeSnapshot.class, log);
        }

        @Override
        protected void doApply(final TakeSnapshot message) {
            final boolean isDryrun = message.getDittoHeaders().isDryRun();
            doLog(logger -> logger.debug("Received request to SaveSnapshot{}. Message: {}",
                    isDryrun ? " (dryrun)" : "",
                    message));

            if (snapshotterState.isInProgress()) {
                // Never save snapshots in parallel. Once snapshotting starts, external TakeSnapshot requests are
                // stashed until snapshotting succeeds, fails or times out.
                persistentActor.stash();
            } else if (isDryrun || canReuseProtectedSnapshot()) {
                // Dryrun or unneeded TakeSnapshot commands result in an immediate response without triggering
                // snapshotting.
                final TakeSnapshotResponse takeSnapshotResponse =
                        TakeSnapshotResponse.of(snapshotterState.getSequenceNr(), message.getDittoHeaders());
                persistentActor.sender().tell(takeSnapshotResponse, persistentActor.self());
            } else {
                doSaveSnapshot(SnapshotTag.PROTECTED, persistentActor.sender(), message.getDittoHeaders());
            }
        }
    }

    /**
     * This strategy handles the {@code TakeSnapshotInternal} message which checks for the need to take a snapshot
     * and does so if needed.
     */
    @NotThreadSafe
    private final class TakeSnapshotInternalStrategy extends AbstractReceiveStrategy<TakeSnapshotInternal> {

        private TakeSnapshotInternalStrategy() {
            super(TakeSnapshotInternal.class, log);
        }

        @Override
        protected void doApply(final TakeSnapshotInternal message) {
            doLog(logger -> logger.debug("Received request to SaveSnapshot. Message: {}", message));
            // if there was any modifying activity since the last taken snapshot:
            if (snapshotterState.isInProgress()) {
                // Never save snapshots in parallel. Once snapshotting starts, internal TakeSnapshot requests are
                // stashed.
                doLog(logger -> logger.debug("Completed internal request for snapshot: Snapshotting is ongoing."));
                persistentActor.stash();
            } else if (noChangeSinceOngoingSnapshot()) {
                // If a snapshot already exists, don't take another one.
                doLog(logger -> logger.debug(
                        "Completed internal request for snapshot: last snapshot of Thing <{}> is up to date.",
                        persistentActor.getThingId()));
            } else {
                doSaveSnapshot(SnapshotTag.UNPROTECTED, null, null);
            }
        }
    }

    /**
     * This strategy handles the success of saving a snapshot by logging the Thing's ID.
     */
    @NotThreadSafe
    private final class SaveSnapshotSuccessStrategy extends AbstractReceiveStrategy<SaveSnapshotSuccess> {

        /**
         * Constructs a new {@code SaveSnapshotSuccessStrategy} object.
         */
        private SaveSnapshotSuccessStrategy() {
            super(SaveSnapshotSuccess.class, log);
        }

        @Override
        protected void doApply(final SaveSnapshotSuccess message) {
            final String thingId = persistentActor.getThingId();
            final SnapshotMetadata snapshotMetadata = message.metadata();
            final long newSnapshotSequenceNr = snapshotMetadata.sequenceNr();

            if (saveSnapshotResponseArrivedOutOfOrder(newSnapshotSequenceNr)) {
                doLog(logger -> logger.warning(
                        "SaveSnapshotSuccess arrived out of order; it will be ignored and its snapshot deleted. " +
                                "Thing=<{}>, newly-arrived-snapshot-metadata=<{}>, snapshotterState=<{}>, " +
                                "lastSaneSnapshotterState=<{}>",
                        thingId, snapshotMetadata, snapshotterState, lastSaneSnapshotterState));
                // safe to delete this snapshot even if it's protected, because the TakeSnapshot sender
                // never got a TakeSnapshotResponse.
                deleteOldSnapshot(newSnapshotSequenceNr, SnapshotTag.UNPROTECTED);
            } else {
                doLog(logger -> logger.debug("Snapshot taken for Thing '{}' with metadata '{}'.", thingId,
                        snapshotMetadata));

                final ActorRef sender = snapshotterState.getSender();
                final DittoHeaders dittoHeaders = snapshotterState.getDittoHeaders();
                if (sender != null) {
                    sender.tell(TakeSnapshotResponse.of(newSnapshotSequenceNr, dittoHeaders),
                            persistentActor.self());
                }

                deleteOldSnapshot(lastSaneSnapshotterState.getSequenceNr(), lastSaneSnapshotterState.getSnapshotTag());
                deleteEventsOlderThan(newSnapshotSequenceNr);

                // will update lastSaneSnapshotterState. Must be called once all access to lastSaneSnapshotterState
                // are finished.
                saveSnapshotSucceeded();
            }
        }

        private void deleteOldSnapshot(final long sequenceNr, final SnapshotTag snapshotTag) {
            // only delete if it's necessary & safe to do so.
            if (snapshotDeleteOld && sequenceNr > 0 && snapshotTag == SnapshotTag.UNPROTECTED) {
                doLog(logger -> logger.info("Deleting <{}> snapshot <{}> of Thing <{}>", snapshotTag, sequenceNr,
                        persistentActor.getThingId()));
                persistentActor.deleteSnapshot(sequenceNr);
            }
        }

        private void deleteEventsOlderThan(final long newSnapshotSequenceNumber) {
            if (eventsDeleteOld && newSnapshotSequenceNumber > 1) {
                /* don't delete the newest event (although not required for restoring thing due to the existing
                   snapshot),
                   because we need it for our persistence queries (ThingTags) which access the journal only.
                 */
                final long upToSequenceNumber = newSnapshotSequenceNumber - 1;
                doLog(logger -> logger.debug("Delete all event messages for Thing '{}' up to sequence number '{}'.",
                        persistentActor.getThingId(), upToSequenceNumber));
                persistentActor.deleteMessages(upToSequenceNumber);
            }
        }
    }

    private void replyErrorMessage(final Supplier<String> errorMessage) {
        final ActorRef sender = snapshotterState.getSender();
        if (sender != null) {
            final DittoRuntimeException exception =
                    ThingUnavailableException.newBuilder(persistentActor.getThingId())
                            .message(errorMessage.get())
                            .dittoHeaders(snapshotterState.getDittoHeaders())
                            .build();
            sender.tell(exception, persistentActor.self());
        }
    }

    /**
     * This strategy handles the failure of saving a snapshot by logging an error.
     */
    @NotThreadSafe
    private final class SaveSnapshotFailureStrategy extends AbstractReceiveStrategy<SaveSnapshotFailure> {

        private SaveSnapshotFailureStrategy() {
            super(SaveSnapshotFailure.class, log);
        }

        @Override
        protected void doApply(final SaveSnapshotFailure message) {
            final long newSnapshotSequenceNr = message.metadata().sequenceNr();
            final String thingId = persistentActor.getThingId();
            if (saveSnapshotResponseArrivedOutOfOrder(newSnapshotSequenceNr)) {
                // this can only happen if timeout happens before snapshot store delivers any response.
                // we cannot delete the new snapshot because we don't know whether it's protected or not.
                doLog(logger -> logger.warning("SaveSnapshotFailure arrived out of order; it will be ignored. " +
                                "Thing=<{}>, newly-arrived-snapshot-metadata=<{}>, snapshotterState=<{}>, " +
                                "lastSaneSnapshotterState=<{}>",
                        thingId, message.metadata(), snapshotterState, lastSaneSnapshotterState));
            } else {
                doLog(logger -> logger.error(message.cause(), "Failed to save Snapshot for {}. Cause: {}.", thingId,
                        message.cause().getMessage()));

                replyErrorMessage(() -> {
                    final Throwable error = message.cause();
                    return String.format("Failed to save snapshot for Thing <%s>. Cause: <%s : %s>", thingId,
                            error.getClass().getCanonicalName(), error.getMessage());
                });

                saveSnapshotFailed();
            }
        }
    }

    /**
     * This strategy handles timeout of saving a snapshot by logging an error.
     */
    @NotThreadSafe
    private final class SaveSnapshotTimeoutStrategy extends AbstractReceiveStrategy<SaveSnapshotTimeout> {

        private SaveSnapshotTimeoutStrategy() {
            super(SaveSnapshotTimeout.class, log);
        }

        @Override
        protected void doApply(final SaveSnapshotTimeout message) {
            final String thingId = persistentActor.getThingId();
            final long failedSequenceNr = message.sequenceNr;
            if (saveSnapshotResponseArrivedOutOfOrder(message.sequenceNr)) {
                doLog(logger -> logger.warning(
                        "SaveSnapshot timed out for seqNr <{}>, but it arrived out of order and will be " +
                                "ignored. ThingId=<{}>, snapshotterState=<{}>, lastSaneSnapshotterState=<{}>",
                        failedSequenceNr, thingId, snapshotterState, lastSaneSnapshotterState));
            } else {
                doLog(logger -> logger.warning(
                        "SaveSnapshot timed out for seqNr <{}>. ThingId=<{}>, snapshotterState=<{}>, " +
                                "lastSaneSnapshotterState=<{}>",
                        failedSequenceNr, thingId, snapshotterState, lastSaneSnapshotterState));
                replyErrorMessage(() -> String.format("Failed to save snapshot for Thing <%s> with sequence " +
                                "number <%s>: The snapshot store failed to respond within ISO-8601 duration %s", thingId,
                        failedSequenceNr, saveSnapshotTimeout));
                saveSnapshotFailed();
            }
        }
    }

    /**
     * This strategy handles the success of deleting a snapshot by logging an info.
     */
    @NotThreadSafe
    private final class DeleteSnapshotSuccessStrategy extends AbstractReceiveStrategy<DeleteSnapshotSuccess> {

        /**
         * Constructs a new {@code DeleteSnapshotSuccessStrategy} object.
         */
        private DeleteSnapshotSuccessStrategy() {
            super(DeleteSnapshotSuccess.class, log);
        }

        @Override
        protected void doApply(final DeleteSnapshotSuccess message) {
            doLog(logger -> logger.debug("Deleting snapshot with sequence number '{}' for Thing '{}' was successful",
                    message.metadata().sequenceNr(), persistentActor.getThingId()));
        }
    }

    /**
     * This strategy handles the failure of deleting a snapshot by logging an error.
     */
    @NotThreadSafe
    private final class DeleteSnapshotFailureStrategy extends AbstractReceiveStrategy<DeleteSnapshotFailure> {

        /**
         * Constructs a new {@code DeleteSnapshotFailureStrategy} object.
         */
        private DeleteSnapshotFailureStrategy() {
            super(DeleteSnapshotFailure.class, log);
        }

        @Override
        protected void doApply(final DeleteSnapshotFailure message) {
            final Throwable cause = message.cause();
            doLog(logger -> logger.error(cause,
                    "Deleting snapshot with sequence number '{}' for Thing '{}' failed. Cause {}: {}",
                    message.metadata().sequenceNr(), persistentActor.getThingId(),
                    cause.getClass().getSimpleName(), cause.getMessage()));
        }
    }

    /**
     * This strategy handles the success of deleting messages by logging an info.
     */
    @NotThreadSafe
    private final class DeleteMessagesSuccessStrategy extends AbstractReceiveStrategy<DeleteMessagesSuccess> {

        /**
         * Constructs a new {@code DeleteMessagesSuccessStrategy} object.
         */
        private DeleteMessagesSuccessStrategy() {
            super(DeleteMessagesSuccess.class, log);
        }

        @Override
        protected void doApply(final DeleteMessagesSuccess message) {
            doLog(logger -> logger.debug("Deleting messages up to seqNr '{}' for Thing '{}' was successful",
                    message.toSequenceNr(), persistentActor.getThingId()));
        }
    }

    /**
     * This strategy handles the failure of deleting messages by logging an error.
     */
    @NotThreadSafe
    private final class DeleteMessagesFailureStrategy extends AbstractReceiveStrategy<DeleteMessagesFailure> {

        /**
         * Constructs a new {@code DeleteMessagesFailureStrategy} object.
         */
        private DeleteMessagesFailureStrategy() {
            super(DeleteMessagesFailure.class, log);
        }

        @Override
        protected void doApply(final DeleteMessagesFailure message) {
            final Throwable cause = message.cause();
            doLog(logger -> logger.error(cause,
                    "Deleting messages up to seqNo '{}' for Thing '{}' failed. Cause {}: {}",
                    persistentActor.getThingId(), message.toSequenceNr(), cause.getClass().getSimpleName(),
                    cause.getMessage()));
        }
    }
}