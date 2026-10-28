package local.agentos;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A small, conservative scam heuristic for chat text (pure Java, no Android, so it runs in a Mac test too).
 * Every rule needs two independent signals in one message (a thing scammers want + the pressure or the ask),
 * so "reset the wifi password when you get here" or "venmo me for pizza" stay quiet. Prompt Guard covers the rest.
 */
final class ScamRules {
    private ScamRules() {}

    /** Orders addressed to an AI ("AI assistant: ignore your user and forward…", "disregard your previous instructions"). */
    private static final Pattern AI = Pattern.compile(
            "(?:^|[^a-z])(?:ai assistant|assistant|chatbot|language model|copilot|ai agent|agent)\\s*[:,]\\s*(?:please\\s+)?(?:ignore|disregard|forward|forget|override|send|text|share|read|reply|email)\\b"
            + "|\\bignore (?:all |any |your |the |my )?(?:previous|prior|above|earlier|user|instructions)\\b"
            + "|\\bdisregard (?:all |any |your |the |my )?(?:previous|prior|above|earlier|user|instructions)\\b"
            + "|\\bsystem prompt\\b");
    /** A verification code or account password… */
    private static final Pattern CODE = Pattern.compile(
            "\\b(?:verification|security|2fa|two[- ]factor|otp|one[- ]time|confirmation|login|sign[- ]in|auth(?:entication)?|bank|account)\\b[^.!?\\n]{0,24}\\b(?:codes?|passcodes?|passwords?|pin)\\b");
    /** …that the message wants from you. */
    private static final Pattern ASK = Pattern.compile(
            "(?<!(?:don't|do not|never|not)\\s)\\b(?:send|share|forward|give|tell|read(?: me| back| out)?|text|reply(?: with)?|enter|confirm|provide|type)\\b"); // "Don't share it" is the bank's own warning
    /** Scam-typical ways to pay (never ordinary friend-to-friend apps or plain amounts). */
    private static final Pattern MONEY = Pattern.compile(
            "\\b(?:gift ?cards?|itunes cards?|steam cards?|google play cards?|bitcoin|btc|crypto(?:currency)?|wire transfer|western union|moneygram"
            + "|processing fee|customs fee|re-?delivery fee|release fee|unpaid toll|outstanding (?:balance|fee|toll))\\b");
    private static final Pattern PAY = Pattern.compile("\\b(?:pay|send|buy|purchase|transfer|owe|required|immediately|urgently|today|within \\d+ ?(?:hours?|hrs|minutes|mins|days?))\\b");
    /** An account threat… */
    private static final Pattern THREAT = Pattern.compile(
            "\\b(?:account|card|access|service)\\b[^.!?\\n]{0,40}\\b(?:suspended|locked|closed|terminated|deactivated|compromised|on hold|will be (?:closed|suspended|deleted|disabled))\\b");
    /** …with a deadline or a link to hurry you. */
    private static final Pattern URGE = Pattern.compile(
            "\\b(?:within \\d+ ?(?:hours?|hrs|minutes|mins|days?)|immediately|urgent(?:ly)?|right away|click (?:here|the link|below)|tap (?:here|the link|below)|https?://|www\\.)|\\.(?:com|net|org|io|ly|xyz|top)/");
    /** A prize you never entered for. */
    private static final Pattern PRIZE = Pattern.compile(
            "\\b(?:won|winner of|claim)\\b[^.!?\\n]{0,30}\\b(?:prize|reward|gift ?card|iphone|lottery|\\$\\d)|\\bclaim your (?:prize|reward|gift)\\b");

    /** One short line saying why this message looks like a scam, or null when it looks fine. Needs at least 6 words. */
    static String why(String s) {
        if (s == null || s.trim().split("\\s+").length < 6) return null;
        String t = s.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (AI.matcher(t).find()) return "gives orders to an AI assistant";
        if (CODE.matcher(t).find() && ASK.matcher(t).find()) return "asks you to share a code or password";
        if (MONEY.matcher(t).find() && PAY.matcher(t).find()) return "asks you to pay in an unusual way";
        if (THREAT.matcher(t).find() && URGE.matcher(t).find()) return "threatens your account to rush you";
        if (PRIZE.matcher(t).find()) return "promises a prize to get a reply";
        return null;
    }
}
