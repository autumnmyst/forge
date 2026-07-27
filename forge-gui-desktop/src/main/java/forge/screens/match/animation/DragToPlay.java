package forge.screens.match.animation;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

import javax.swing.SwingUtilities;

import org.tinylog.Logger;

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
        finishDrag();

        if (!wasArmed) {
            // Released back over the hand: undo the arming and let the drag mean what it
            // has always meant, a reorder.
            cancelCast();
            return;
        }
        consumed = true;
        // The prompt only offers Auto when the whole cost is payable from untapped
        // sources. If it is not offering, the cast needs more decisions and the normal
        // prompts take over from here.
        final IGameController controller = controller();
        if (controller != null && matchUI.isAutoPayOffered()) {
            controller.selectButtonOk();
            if (sourceCard != null) {
                animator.slideIntoPlace(sourceCard, toScreen(drop));
            }
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
        // Only retract while the prompt is still willing to be cancelled. Once the game
        // has moved past the payment there is nothing here that should be undoing it.
        final IGameController controller = controller();
        if (controller != null && matchUI.isCancelOffered()) {
            controller.selectButtonCancel();
        }
        sourceCard = null;
    }

    private void finishDrag() {
        if (ghost != null) {
            animator.getLayer().removeOverlayAnim(ghost);
            ghost.finish();
            ghost = null;
        }
        if (sourcePanel != null) {
            sourcePanel.clearRenderTransform();
            sourcePanel.repaint();
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
        final Point p = ghost != null ? ghost.getPosition() : new Point(0, 0);
        return new MouseEvent(animator.getLayer(), MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, p.x, p.y, 1, false, MouseEvent.BUTTON1);
    }
}
