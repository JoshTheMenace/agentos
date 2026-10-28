package local.agentos;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Is this touch a real finger on the glass? Our cards accept only such touches, so the agent can't press them.
 * Rejected: touches made by software (an accessibility gesture arrives on the virtual device with TOOL_TYPE_UNKNOWN;
 * `input tap` has no contact area, size 1.0 and touchMajor 0, while a finger reports its area), touches through another
 * window drawn over ours (tapjacking), non-touchscreen sources, and anything in the first 500 ms (a tap already in
 * flight when the card popped up isn't a decision). Use as a button's touch filter: return !HumanTouch.real(e, shownAt).
 * Not used: MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT (0x800). On Android 13/14 the accessibility input filter stamps it on
 * EVERY touch while a gesture-capable service (ours) is enabled (MotionEventInjector.onMotionEvent), so checking it
 * rejected the owner's own finger.
 */
final class HumanTouch {
    private static final int OBSCURED = MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
    static final long MIN_DELAY_MS = 500;

    private HumanTouch() {}

    /** shownAt = SystemClock.uptimeMillis() when the card appeared. */
    static boolean real(MotionEvent e, long shownAt) {
        return !injected(e) && (e.getFlags() & OBSCURED) == 0 && e.getEventTime() - shownAt >= MIN_DELAY_MS;
    }

    /** Made by software, not a finger. */
    static boolean injected(MotionEvent e) {
        InputDevice d = e.getDevice();
        return !e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
                || d == null || d.isVirtual();
    }

    static boolean settled(long shownAt) { return SystemClock.uptimeMillis() - shownAt >= MIN_DELAY_MS; }
}
