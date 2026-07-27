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
 * The gesture is entirely visual until it ends. Nothing is played, cancelled or
 * answered while the card is moving; releasing it over your own battlefield plays it,
 * by the same call a click makes, and everything after that is the ordinary cast.
 * <p>
 * Committing earlier was tried and does not work. A cast begins asking for targets and
 * modal choices the moment the card clears the hand, and there is no way to answer
 * those with the mouse button still down - the questions arrive mid-gesture, over a
 * board the player is in the middle of dragging across.
 * <p>
 * So this class never needs to know which costs a card has or which prompts it will
 * raise: it hands the card to the game and gets out of the way. The animator keeps
 * carrying the picture of it from the point it was dropped until the cast is decided.
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

        // Dragging is purely visual: nothing is played, cancelled or answered until the
        // card is let go. Committing part-way through meant a spell began asking for
        // targets and modal choices the instant the card cleared the hand, while it was
        // still being held, and there is no sensible way to answer those mid-gesture.
        ghost.setArmed(isOverOwnBattlefield(inLayer));
    }

    private void onDragEnd() {
        if (ghost == null) {
            return;
        }
        final Point drop = ghost.getPosition();
        final boolean overField = ghost.isArmed();
        final CardView held = sourcePanel == null ? null : sourcePanel.getCard();
        final CardSnapshot snap = snapshot;
        finishDrag();

        // Released anywhere but your own battlefield, or not playable right now: this
        // was a hand reorder, and nothing has been touched.
        if (!overField || !canPlayNow(held)) {
            return;
        }
        consumed = true;
        sourceCard = held;
        // Take the card over at the point it was let go, before the play begins. Playing
        // it removes it from the hand, and without this the hand's removal hook would
        // pick it up again from the empty slot it just left.
        animator.holdAt(held, snap, drop);
        // From here it is an ordinary play: whatever the cast needs - targets, choices,
        // mana - it asks for in its own prompts, exactly as clicking the card would.
        controller().selectCard(held, null, new MouseTriggerEvent(syntheticEvent(drop)));
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
