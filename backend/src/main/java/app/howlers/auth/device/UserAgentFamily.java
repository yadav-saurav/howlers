package app.howlers.auth.device;

/**
 * Coarse browser and OS family from a User-Agent header (D-041): "Firefox" and "Windows", never versions or device
 * models, so the fingerprint survives browser updates and says little about the person. Order matters: Edge and
 * Opera also claim to be Chrome, and Chrome claims to be Safari.
 */
public record UserAgentFamily(String browser, String os) {
    public static final String UNKNOWN_BROWSER = "Unknown browser";
    public static final String UNKNOWN_OS = "Unknown OS";

    public static UserAgentFamily parse(String ua) {
        if (ua == null || ua.isBlank()) return new UserAgentFamily(UNKNOWN_BROWSER, UNKNOWN_OS);
        return new UserAgentFamily(browser(ua), os(ua));
    }

    static String browser(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("SamsungBrowser/")) return "Samsung Internet";
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS/") || ua.contains("Chromium/")) return "Chrome";
        if (ua.contains("Safari/") && ua.contains("Version/")) return "Safari";
        if (ua.startsWith("curl/")) return "curl";
        return UNKNOWN_BROWSER;
    }

    static String os(String ua) {
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) return "iOS";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("CrOS")) return "ChromeOS";
        if (ua.contains("Mac OS X") || ua.contains("Macintosh")) return "macOS";
        if (ua.contains("Linux") || ua.contains("X11")) return "Linux";
        return UNKNOWN_OS;
    }

    /** "Firefox on Windows". */
    public String describe() {
        return browser + " on " + os;
    }
}
