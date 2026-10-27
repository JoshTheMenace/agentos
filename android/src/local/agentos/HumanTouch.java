package local.agentos;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Is this touch a real finger on the glass? Our cards accept only such touches, so the agent can't press them.
 * Rejected: touches made by an accessibility service (the agent's hands, flag 0x800), touches through another window
 * drawn over ours (tapjacking), non-touchscreen sources, and anything in the first 500 ms (a tap already in flight
 * when the card popped up isn't a decision). Use as a button's touch filter: return !HumanTouch.real(e, shownAt).
 */
final class HumanTouch {
    private static final int FLAG_FROM_ACCESSIBILITY = 0x800; // MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT (hidden)
    static final long MIN_DELAY_MS = 500;

    private HumanTouch() {}

    /** shownAt = SystemClock.uptimeMillis() when the card appeared. */
    static boolean real(MotionEvent e, long shownAt) {
        int bad = FLAG_FROM_ACCESSIBILITY | MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
        return (e.getFlags() & bad) == 0 && e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                && e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && e.getEventTime() - shownAt >= MIN_DELAY_MS;
    }

    static boolean settled(long shownAt) { return SystemClock.uptimeMillis() - shownAt >= MIN_DELAY_MS; }
}
