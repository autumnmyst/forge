package forge.screens.match.animation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

/**
 * The queue's contract is that the board always ends up matching the game, whatever
 * happens to the animations on the way. These cover that: hooks run exactly once, in
 * order, whether a step played out, was accelerated, was skipped, or threw.
 */
public class AnimationQueueTest {

    /** An animation that does nothing but take time. */
    private static Anim dummy(final long ms) {
        return new Anim(ms) {
            @Override
            protected void update(final float t) {
            }
        };
    }

    private static AnimationStep step(final String label, final List<String> log, final long ms) {
        return new AnimationStep(label)
                .add(dummy(ms))
                .before(() -> log.add(label + ":before"))
                .after(() -> log.add(label + ":after"));
    }

    @Test
    public void stepsRunInOrderAndExactlyOnce() {
        final List<String> log = new ArrayList<>();
        final AnimationQueue q = new AnimationQueue();
        q.enqueue(step("a", log, 100));
        q.enqueue(step("b", log, 100));

        for (int i = 0; i < 40 && !q.isIdle(); i++) {
            q.tick(16);
        }

        assertTrue(q.isIdle(), "queue should drain");
        assertEquals(log, List.of("a:before", "a:after", "b:before", "b:after"));
    }

    @Test
    public void oneStepPlaysAtATime() {
        final List<String> log = new ArrayList<>();
        final AnimationQueue q = new AnimationQueue();
        q.enqueue(step("a", log, 500));
        q.enqueue(step("b", log, 500));

        q.tick(16);
        assertEquals(log, List.of("a:before"), "the second step must not start early");
    }

    @Test
    public void skipAllRunsEveryPendingHookInOrder() {
        final List<String> log = new ArrayList<>();
        final AnimationQueue q = new AnimationQueue();
        for (int i = 0; i < 5; i++) {
            q.enqueue(step("s" + i, log, 10_000));
        }
        q.tick(16);
        q.skipAll();

        assertTrue(q.isIdle());
        assertEquals(log.size(), 10, "every step contributes a before and an after");
        for (int i = 0; i < 5; i++) {
            assertEquals(log.get(i * 2), "s" + i + ":before");
            assertEquals(log.get(i * 2 + 1), "s" + i + ":after");
        }
    }

    @Test
    public void skippedAnimationsStillReachTheirEndState() {
        final float[] lastSeen = { -1f };
        final AnimationQueue q = new AnimationQueue();
        q.enqueue(new AnimationStep("x").add(new Anim(10_000) {
            @Override
            protected void update(final float t) {
                lastSeen[0] = t;
            }
        }));

        q.tick(16);
        assertTrue(lastSeen[0] < 1f, "sanity: mid-flight");
        q.skipAll();
        assertEquals(lastSeen[0], 1f, "a cut-short animation must still apply its final frame");
    }

    @Test
    public void backlogIsBoundedAndNothingIsLost() {
        final List<String> log = new ArrayList<>();
        final AnimationQueue q = new AnimationQueue();
        // Far more than the queue will animate, each long enough to never finish normally.
        for (int i = 0; i < 60; i++) {
            q.enqueue(step("s" + i, log, 10_000));
        }
        q.tick(16);

        assertTrue(q.getDepth() <= 21, "backlog should be trimmed, was " + q.getDepth());
        // Everything dropped must still have been committed, in order.
        assertFalse(log.isEmpty());
        for (int i = 0; i < log.size() / 2; i++) {
            assertEquals(log.get(i * 2), "s" + i + ":before");
            assertEquals(log.get(i * 2 + 1), "s" + i + ":after");
        }
    }

    @Test
    public void aThrowingHookDoesNotWedgeTheQueue() {
        final List<String> log = new ArrayList<>();
        final AnimationQueue q = new AnimationQueue();
        q.enqueue(new AnimationStep("bad").after(() -> {
            throw new IllegalStateException("boom");
        }));
        q.enqueue(step("good", log, 50));

        for (int i = 0; i < 20 && !q.isIdle(); i++) {
            q.tick(16);
        }

        assertTrue(q.isIdle(), "a failing hook must not block later steps");
        assertEquals(log, List.of("good:before", "good:after"));
    }

    @Test
    public void emptyStepsDrainWithoutCostingFrames() {
        final List<String> log = new ArrayList<>();
        final AnimationQueue q = new AnimationQueue();
        for (int i = 0; i < 6; i++) {
            final String label = "n" + i;
            q.enqueue(new AnimationStep(label).after(() -> log.add(label)));
        }

        q.tick(16);

        assertTrue(q.isIdle(), "pure state changes should not be paced one per frame");
        assertEquals(log.size(), 6);
    }

    @Test
    public void pausedQueueHoldsWithoutDropping() {
        final List<String> log = new ArrayList<>();
        final AnimationQueue q = new AnimationQueue();
        q.enqueue(step("a", log, 50));
        q.setPaused(true);

        for (int i = 0; i < 10; i++) {
            q.tick(16);
        }
        assertTrue(log.isEmpty(), "nothing should run while paused");

        q.setPaused(false);
        for (int i = 0; i < 10 && !q.isIdle(); i++) {
            q.tick(16);
        }
        assertEquals(log, List.of("a:before", "a:after"));
    }
}
