package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class AprsBeaconCoordinatorTest {
    private static final long WALL = 1_775_000_000_000L;

    @Test
    public void rejectsStaleAndPoorFixes() {
        AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
        assertFalse(coordinator.onLocation(fix(13.0, 100.0, 150.0f, 0L,
                0.0f, 0.0f), WALL, 1_000L).isLocationAccepted());
        assertFalse(coordinator.onLocation(fix(13.0, 100.0, 10.0f, -180_000L,
                0.0f, 0.0f), WALL, 1_000L).isLocationAccepted());
    }

    @Test
    public void stationaryJitterQueuesOnlyFirstFix() {
        AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
        assertTrue(coordinator.onLocation(fix(13.000000, 100.000000, 12.0f, 0L,
                0.0f, 0.0f), WALL, 1_000L).isBeaconQueued());
        assertFalse(coordinator.onLocation(fix(13.000030, 100.000020, 15.0f, 10_000L,
                0.0f, 0.0f), WALL + 10_000L, 11_000L).isBeaconQueued());
        assertEquals(AprsBeaconCoordinator.MovementState.STATIONARY,
                coordinator.getMovementState());
        assertEquals(1, coordinator.getQueuedBeaconCount());
    }

    @Test
    public void walkingAndVehicleTransitionsQueueEarlyUpdates() {
        AprsBeaconCoordinator coordinator = successfulAtOrigin();
        AprsBeaconCoordinator.Decision walking = coordinator.onLocation(
                fix(13.0012, 100.0, 8.0f, 60_000L, 1.4f, 0.0f),
                WALL + 60_000L, 61_000L);
        assertTrue(walking.isBeaconQueued());
        assertEquals(AprsBeaconCoordinator.TriggerReason.STARTED_MOVING,
                walking.getTriggerReason());
        drainSuccess(coordinator, 61_000L);

        AprsBeaconCoordinator.Decision vehicle = coordinator.onLocation(
                fix(13.0060, 100.0, 7.0f, 160_000L, 15.0f, 0.0f),
                WALL + 160_000L, 161_000L);
        assertTrue(vehicle.isBeaconQueued());
        assertEquals(AprsBeaconCoordinator.MovementState.VEHICLE,
                vehicle.getMovementState());
    }

    @Test
    public void significantTurnAllowsEarlierVehicleBeacon() {
        AprsBeaconCoordinator coordinator = successfulAtOrigin();
        coordinator.onLocation(fix(13.0020, 100.0, 5.0f, 30_000L, 12.0f, 0.0f),
                WALL + 30_000L, 31_000L);
        drainSuccess(coordinator, 31_000L);
        coordinator.onLocation(fix(13.0060, 100.0, 5.0f, 25_000L, 15.0f, 0.0f),
                WALL + 55_000L, 56_000L);
        drainSuccess(coordinator, 56_000L);

        AprsBeaconCoordinator.Decision turn = coordinator.onLocation(
                fix(13.0060, 100.0040, 5.0f, 100_000L, 15.0f, 90.0f),
                WALL + 130_000L, 131_000L);
        assertTrue(turn.isBeaconQueued());
        assertEquals(AprsBeaconCoordinator.TriggerReason.TURN, turn.getTriggerReason());
    }

    @Test
    public void movingToStationaryQueuesFinalStop() {
        AprsBeaconCoordinator coordinator = successfulAtOrigin();
        coordinator.onLocation(fix(13.0020, 100.0, 5.0f, 30_000L, 2.0f, 0.0f),
                WALL + 30_000L, 31_000L);
        drainSuccess(coordinator, 31_000L);
        AprsBeaconCoordinator.Decision stopped = coordinator.onLocation(
                fix(13.00201, 100.0, 5.0f, 130_000L, 0.0f, 0.0f),
                WALL + 160_000L, 161_000L);
        assertTrue(stopped.isBeaconQueued());
        assertEquals(AprsBeaconCoordinator.TriggerReason.STOPPED,
                stopped.getTriggerReason());
    }

    @Test
    public void repeatedPttAndSmartBeaconShareDuplicateProtection() {
        AprsBeaconCoordinator coordinator = successfulAtOrigin();
        assertFalse(coordinator.onPtt(WALL + 10_000L, 11_000L).isBeaconQueued());
        coordinator.onLocation(fix(13.0020, 100.0, 5.0f, 120_000L, 1.5f, 0.0f),
                WALL + 120_000L, 121_000L);
        assertFalse(coordinator.onPtt(WALL + 121_000L, 122_000L).isBeaconQueued());
        assertEquals(1, coordinator.getQueuedBeaconCount());
    }

    @Test
    public void failureRetriesSameLogicalBeaconAndNewerFixReplacesIt() {
        AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
        coordinator.onLocation(fix(13.0, 100.0, 5.0f, 0L, 0.0f, 0.0f),
                WALL, 1_000L);
        AprsBeaconCoordinator.Beacon first = coordinator.takeReady(1_000L);
        assertNotNull(first);
        coordinator.onSendFailure(first.getLogicalId(), true, 1_000L);
        assertNull(coordinator.takeReady(10_000L));

        coordinator.onLocation(fix(13.0100, 100.0, 5.0f, 30_000L, 15.0f, 0.0f),
                WALL + 30_000L, 31_000L);
        AprsBeaconCoordinator.Beacon newer = coordinator.takeReady(31_000L);
        assertNotNull(newer);
        assertTrue(newer.getLogicalId() > first.getLogicalId());
    }

    @Test
    public void permanentFailureDropsInFlightBeaconWithoutRetry() {
        AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
        coordinator.onLocation(fix(13.0, 100.0, 5.0f, 0L, 0.0f, 0.0f), WALL, 1_000L);
        AprsBeaconCoordinator.Beacon beacon = coordinator.takeReady(1_000L);
        assertNotNull(beacon);
        coordinator.onPermanentFailure(beacon.getLogicalId());
        assertEquals(0, coordinator.getQueuedBeaconCount());
        assertNull(coordinator.takeReady(60_000L));
    }

    @Test
    public void restartRestoreSuppressesRecentDuplicateButAllowsHeartbeat() {
        AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
        TrackingFix persisted = fix(13.0, 100.0, 8.0f, 0L, 0.0f, 0.0f);
        coordinator.restoreLastSuccessful(persisted,
                AprsBeaconCoordinator.MovementState.STATIONARY, 1_000L);
        assertFalse(coordinator.onLocation(fix(13.00001, 100.00001, 8.0f, 10_000L,
                0.0f, 0.0f), WALL + 10_000L, 11_000L).isBeaconQueued());
        assertTrue(coordinator.onLocation(fix(13.00001, 100.00001, 8.0f,
                3_600_001L, 0.0f, 0.0f), WALL + 3_600_001L,
                3_601_001L).isBeaconQueued());
    }

    @Test
    public void objectIdentityChangeAllowsCurrentPositionToBePublishedAgain() {
        AprsBeaconCoordinator coordinator = successfulAtOrigin();
        coordinator.resetForObjectIdentity();

        AprsBeaconCoordinator.Decision decision = coordinator.onLocation(
                fix(13.00001, 100.00001, 8.0f, 10_000L, 0.0f, 0.0f),
                WALL + 10_000L, 11_000L);

        assertTrue(decision.isBeaconQueued());
        assertEquals(AprsBeaconCoordinator.TriggerReason.FIRST_FIX,
                decision.getTriggerReason());
    }

    @Test
    public void objectIdentityChangeIgnoresReceiptForOldInFlightBeacon() {
        AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
        coordinator.onLocation(fix(13.0, 100.0, 5.0f, 0L, 0.0f, 0.0f), WALL, 1_000L);
        AprsBeaconCoordinator.Beacon oldIdentity = coordinator.takeReady(1_000L);
        assertNotNull(oldIdentity);

        coordinator.resetForObjectIdentity();

        assertFalse(coordinator.onSendSuccess(oldIdentity.getLogicalId(), 2_000L));
        assertEquals(0, coordinator.getQueuedBeaconCount());
    }

    @Test
    public void concurrentIdenticalCallbacksQueueOneLogicalBeacon() throws Exception {
        AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                coordinator.onLocation(fix(13.0, 100.0, 5.0f, 0L,
                        0.0f, 0.0f), WALL, 1_000L);
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
        assertEquals(1, coordinator.getQueuedBeaconCount());
    }

    private static AprsBeaconCoordinator successfulAtOrigin() {
        AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
        coordinator.onLocation(fix(13.0, 100.0, 5.0f, 0L, 0.0f, 0.0f),
                WALL, 1_000L);
        drainSuccess(coordinator, 1_000L);
        return coordinator;
    }

    private static void drainSuccess(AprsBeaconCoordinator coordinator, long elapsed) {
        AprsBeaconCoordinator.Beacon beacon = coordinator.takeReady(elapsed);
        if (beacon != null) {
            coordinator.onSendSuccess(beacon.getLogicalId(), elapsed);
        }
    }

    private static TrackingFix fix(double latitude, double longitude, float accuracy,
                                   long elapsedDelta, float speed, float bearing) {
        long elapsed = 1_000L + elapsedDelta;
        return new TrackingFix(latitude, longitude, accuracy, WALL + elapsedDelta,
                elapsed, speed, bearing);
    }
}
