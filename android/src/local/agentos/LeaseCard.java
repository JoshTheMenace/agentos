package local.agentos;

import android.accessibilityservice.AccessibilityService;

/**
 * The one-tap "Allow agent in Pigeon for 10 min" card shown when a program (not a person) calls task.start.
 * It opens out of the Island (A2 look) through ApprovalCard.lease(); only HumanTouch.real() touches reach allow()/deny().
 */
final class LeaseCard {
    static final class Model {
        String apps = "", minutes = "", goal = "", caller = "";
    }

    interface Listener { void allow(); void deny(); }

    private final AgentA11y svc;

    LeaseCard(AccessibilityService svc) { this.svc = (AgentA11y) svc; }

    /** Main thread. */
    void show(Model m, Listener l) {
        svc.card.lease(m.apps, m.minutes, m.goal, m.caller, new ApprovalCard.Listener() {
            @Override public void approve() { l.allow(); }
            @Override public void deny() { l.deny(); }
        });
    }

    /** Main thread. */
    void hide() { svc.card.hide(); }
}
