package forge.screens.match.animation;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

import javax.swing.SwingUtilities;

import org.tinylog.Logger;

import forge.card.mana.ManaCost;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.screens.match.views.VField;
import forge.toolbox.MouseTriggerEvent;
import forge.view.arcane.CardPanel;
import forge.view.arcane.util.CardPanelMouseAdapter;

/**
 * Drag a card out of hand and drop it on the battlefield to play it.
 * <p>
 * The flow deliberately reuses the existing cast machinery rather than reimplementing
 * any of it. Pulling a card up onto the battlefield <em>arms</em> it, which starts the
 * normal cast exactly as a click would; the game then puts up its own payment prompt
 * and lights up the lands auto-tap would use, all while the card is still in the air.
 * Releasing presses the prompt's Auto button. Dragging back down cancels it.
 * <p>
 * The consequence is that anything the payment cannot settle by itself - choosing
 * targets, announcing X, an additional cost - simply falls through to the flow that
 * exists today: the card leaves the hand and the game asks its questions. Nothing here
 * needs to know which costs those are, because it never tries to pay them.
 * <p>
 * Dragging <em>within</em> the hand still reorders, untouched. The two gestures are
 * told apart by where the pointer is, not by how the drag started.
 */
public final class DragToPlay extends CardPanelMouseAdapter {

    private final CMatchUI matchUI;
    private final PlayerView player;
    private final MatchAnimator animator;

    private CardPanel sourcePanel;
    /** Picture of the dragged card, handed to the animator so it can keep carrying it. */
    private CardSnapshot snapshot;
    /** The card whose cast this drag started, so it can be cancelled or completed. */
    private CardView sourceCard;
    private DragGhost ghost;
    private boolean armed;
    private boolean consumed;

    public DragToPlay(final CMatchUI matchUI, final PlayerView player, final MatchAnimator animator) {
        this.matchUI = matchUI;
        this.player = player;
        this.animator = animator;
    }

    private boolean isEnabled() {
        return animator.isEnabled() && FModel.getPreferences().getPrefBoolean(FPref.UI_DRAG_CARDS_TO_PLAY);
    }

    /**
     * Whether this card could be played by clicking it right now.
     * <p>
     * A card must not be picked up outside the player's own priority window. Before the
     * first turn, during mulligans, and whenever it is someone else's window there is no
     * input that would accept a card being played, and driving the cast machinery anyway
     * reaches {@code getGameController()}, which falls back to a spectator controller
     * that does not exist yet.
     * <p>
     * Priority alone is not enough either - it says the player may act, not that this
     * card is something the current input will take. {@code getActivateDescription} asks
     * the live input directly, and is null unless it would really do something.
     */
    private boolean canPlayNow(final CardView card) {
        if (card == null || player == null || !player.getHasPriority()) {
            return false;
        }
        final IGameController controller = controller();
        return controller != null && controller.getActivateDescription(card) != null;
    }

    /** The controller for this hand's own player; never the spectator fallback. */
    private IGameController controller() {
        return matchUI.getGameController(player);
    }

    /**
     * Whether playing this card will put up a mana payment the player can watch.
     * <p>
     * Only such a card is committed early, when the pointer crosses onto the
     * battlefield, so the payment prompt and auto-tap highlights are visible while it is
     * still being held. Everything else waits for release.
     * <p>
     * A land, a zero-cost artifact or a free spell has nothing to pay, so committing it
     * early would resolve it outright the instant the pointer entered the battlefield -
     * the card would leave the hand mid-drag, still-held, and leave the drag operating
     * on a panel the zone refresh has already disposed. Waiting for release also matches
     * what the gesture means: you put the card down when you let go of it.
     */
    private static boolean needsPayment(final CardView card) {
        if (card == null || card.getCurrentState() == null) {
            return false;
        }
        final ManaCost cost = card.getCurrentState().getManaCost();
        return cost != null && !cost.isNoCost() && !cost.isZero();
    }

    /**
     * Whether the drag just finished was a play rather than a reorder, so the hand
     * controller can leave the card order alone.
     */
    public boolean consumedLastDrag() {
        return consumed;
    }

    /**
     * Run a drag handler, swallowing any failure.
     * <p>
     * These are mouse callbacks on the EDT, so an exception escaping one would surface
     * as a crash in the middle of a match. Dragging is a convenience over clicking, and
     * no failure in it is worth taking the game down for - the drag is abandoned and
     * clicking still works.
     */
    private void guard(final Runnable body) {
        try {
            body.run();
        } catch (final RuntimeException e) {
            Logger.error(e, "Drag-to-play failed; abandoning the drag");
            try {
                finishDrag();
            } catch (final RuntimeException ignored) {
                // Already failing; do not mask the original cause.
            }
        }
    }

    @Override
    public void mouseDragStart(final CardPanel dragPanel, final MouseEvent evt) {
        guard(() -> onDragStart(dragPanel, evt));
    }

    @Override
    public void mouseDragged(final CardPanel dragPanel, final int dragOffsetX, final int dragOffsetY,
            final MouseEvent evt) {
        guard(() -> onDragged(evt));
    }

    @Override
    public void mouseDragEnd(final CardPanel dragPanel, final MouseEvent evt) {
        guard(this::onDragEnd);
    }

    private void onDragStart(final CardPanel dragPanel, final MouseEvent evt) {
        consumed = false;
        armed = false;
        sourcePanel = null;
        sourceCard = null;
        if (!isEnabled() || dragPanel == null || !canPlayNow(dragPanel.getCard())) {
            // Not playable right now, so this drag is a hand reorder like it always was.
            return;
        }
        final CardSnapshot snap = CardSnapshot.capture(dragPanel, animator.getLayer());
        if (snap == null) {
            return;
        }
        sourcePanel = dragPanel;
        snapshot = snap;
        ghost = new DragGhost(snap, snap.getCenter());
        animator.getLayer().addOverlayAnim(ghost);
        animator.getClock().start();
        // Hide the original so the card looks picked up rather than duplicated.
        dragPanel.setRenderAlpha(0f);
        dragPanel.repaint();
    }

    private void onDragged(final MouseEvent evt) {
        if (ghost == null || sourcePanel == null) {
            return;
        }
        final Point inLayer = toLayer(evt);
        if (inLayer == null) {
            return;
        }
        ghost.setTarget(inLayer);

        final boolean overField = isOverOwnBattlefield(inLayer);
        ghost.setArmed(overField);
        // Only a card with a cost to pay is committed here; the rest are played on
        // release, so the ghost still shows as droppable without the card being gone.
        if (!needsPayment(sourcePanel.getCard())) {
            return;
        }
        if (overField && !armed) {
            arm();
        } else if (!overField && armed) {
            disarm();
        }
    }

    private void onDragEnd() {
        if (ghost == null) {
            return;
        }
        final Point drop = ghost.getPosition();
        final boolean wasArmed = armed;
        final boolean overField = ghost.isArmed();
        final CardView held = sourcePanel == null ? null : sourcePanel.getCard();
        // Once a cast has begun the card is no longer a hand card, wherever the pointer
        // ended up. Claiming the drag stops the hand controller reordering a slot that
        // is not there any more.
        final boolean castStarted = sourceCard != null;
        if (castStarted) {
            // Hand the card over to the animator at the point it was released, so it
            // carries on hovering there while the cost is paid rather than snapping back
            // to the hand slot it came from.
            animator.holdAt(sourceCard, snapshot, drop);
        }
        finishDrag();
        consumed = castStarted;

        if (!overField) {
            // Released back over the hand: retract anything already begun and let the
            // drag mean what it has always meant, a reorder.
            cancelCast();
            return;
        }

        if (!wasArmed) {
            // Nothing to pay, so the play was held back until now. Start it here and let
            // it run to completion on its own - this is the release that plays a land.
            if (!canPlayNow(held)) {
                return;
            }
            consumed = true;
            sourceCard = held;
            controller().selectCard(held, null, new MouseTriggerEvent(syntheticEvent(drop)));
            animator.slideIntoPlace(held, toScreen(drop));
            return;
        }

        consumed = true;
        // The prompt only offers Auto when the whole cost is payable from untapped
        // sources. If it is not offering, the cast needs more decisions and the normal
        // prompts take over from here.
        final IGameController controller = controller();
        if (controller != null && matchUI.isAutoPayOffered()) {
            // No slide from here: a card that goes through the stack is already being
            // carried by the animator from the moment it left the hand, and it lands
            // itself once it resolves.
            controller.selectButtonOk();
        }
    }

    /** Begin the cast, exactly as a left click on the card would. */
    private void arm() {
        armed = true;
        if (sourcePanel == null) {
            return;
        }
        // Re-checked rather than trusted from drag start: priority can pass while the
        // card is still being held.
        final CardView card = sourcePanel.getCard();
        if (!canPlayNow(card)) {
            return;
        }
        sourceCard = card;
        // A synthetic trigger event keeps the multi-ability popup working; if one opens,
        // that is the "needs more steps" path and the drag simply stops mattering.
        controller().selectCard(sourceCard, null, new MouseTriggerEvent(syntheticEvent()));
    }

    /** Pull the card back out of the battlefield before releasing: undo the cast. */
    private void disarm() {
        armed = false;
        cancelCast();
    }

    private void cancelCast() {
        if (sourceCard == null) {
            return;
        }
        sourceCard = null;
        // Retract only the mana payment this drag put up. Targeting happens before
        // payment, so by the time a targeted spell reaches the board the prompt asking
        // for something is not ours - pressing its Cancel would abort the target
        // selection the player is in the middle of and bounce the card back to hand,
        // which is precisely what moving the pointer off the battlefield used to do.
        if (!matchUI.isPaymentPrompt() || matchUI.isSelecting()) {
            return;
        }
        final IGameController controller = controller();
        if (controller != null && matchUI.isCancelOffered()) {
            controller.selectButtonCancel();
        }
    }

    private void finishDrag() {
        if (ghost != null) {
            animator.getLayer().removeOverlayAnim(ghost);
            ghost.finish();
            ghost = null;
        }
        if (sourcePanel != null) {
            // Only if it still holds a card: a panel whose card has been played is
            // already disposed, and touching it revives an unpaintable component.
            if (sourcePanel.getCard() != null) {
                sourcePanel.clearRenderTransform();
                sourcePanel.repaint();
            }
            sourcePanel = null;
        }
        armed = false;
        animator.getLayer().repaint();
    }

    // ------------------------------------------------------------------ geometry

    private boolean isOverOwnBattlefield(final Point inLayer) {
        final VField field = matchUI.getFieldViewFor(player);
        if (field == null) {
            return false;
        }
        final Rectangle bounds = animator.boundsInLayer(field.getTabletop());
        return bounds != null && bounds.contains(inLayer);
    }

    private Point toLayer(final MouseEvent evt) {
        final java.awt.Component src = evt.getComponent();
        if (src == null || !src.isShowing() || !animator.getLayer().isShowing()) {
            return null;
        }
        return SwingUtilities.convertPoint(src, evt.getPoint(), animator.getLayer());
    }

    private Point toScreen(final Point inLayer) {
        if (!animator.getLayer().isShowing()) {
            return null;
        }
        final Point p = new Point(inLayer);
        SwingUtilities.convertPointToScreen(p, animator.getLayer());
        return p;
    }

    /**
     * The cast path wants a mouse event to anchor a popup menu against. The drag's own
     * event is consumed by the time we need one, so synthesize a positionally
     * equivalent stand-in over the layer.
     */
    private MouseEvent syntheticEvent() {
        return syntheticEvent(ghost != null ? ghost.getPosition() : new Point(0, 0));
    }

    private MouseEvent syntheticEvent(final Point at) {
        return new MouseEvent(animator.getLayer(), MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, at.x, at.y, 1, false, MouseEvent.BUTTON1);
    }
}
