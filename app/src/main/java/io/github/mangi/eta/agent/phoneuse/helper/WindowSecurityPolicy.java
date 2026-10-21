package io.github.mangi.eta.agent.phoneuse.helper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative display-local FLAG_SECURE inspection; never infer safety from a black screenshot. */
final class WindowSecurityPolicy {
    private static final Pattern WINDOW = Pattern.compile("(?m)^\\s*Window #\\d+ Window\\{");
    private static final Pattern DISPLAY = Pattern.compile("\\bmDisplayId=(\\d+)\\b");
    private static final Pattern FLAGS = Pattern.compile("\\bfl=(?:0x)?([0-9a-fA-F]+)(?=[\\s}])");

    static String inspect(String dump, int displayId, boolean hasForeground) {
        if (displayId <= 0 || dump == null || !dump.contains("WINDOW MANAGER WINDOWS")) return "UNKNOWN";
        String[] windows = WINDOW.split(dump);
        if (windows.length == 1 && dump.contains("Window #")) return "UNKNOWN";
        int inspected = 0;
        for (int i = 1; i < windows.length; i++) {
            String window = windows[i];
            Matcher display = DISPLAY.matcher(window);
            if (!display.find()) return "UNKNOWN";
            try {
                if (Integer.parseInt(display.group(1)) != displayId) continue;
            } catch (NumberFormatException invalid) { return "UNKNOWN"; }
            // Hidden secure windows are also rejected: the policy is deliberately conservative.
            if (Pattern.compile("\\b(?:FLAG_)?SECURE\\b").matcher(window).find()) return "SECURE";
            Matcher flags = FLAGS.matcher(window);
            if (!flags.find()) {
                // Modern Android prints LayoutParams flags symbolically; unknown/missing formats fail closed.
                if (Pattern.compile("\\bfl=[A-Z_][A-Z_ |]*(?=[\\s}])").matcher(window).find()) { inspected++; continue; }
                return "UNKNOWN";
            }
            try {
                if ((Long.parseUnsignedLong(flags.group(1), 16) & 0x2000L) != 0) return "SECURE";
            } catch (NumberFormatException invalid) { return "UNKNOWN"; }
            inspected++;
        }
        return inspected > 0 || !hasForeground ? "SAFE" : "UNKNOWN";
    }
}
