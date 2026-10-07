package dev.briefestboxer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Shared client settings for entity highlights and Sulfur Cube guides. */
public final class BriefestBoxerConfig {
    private static final int[] PALETTE = {0xFF4040, 0x27D8FF, 0xB26BFF, 0xFFB347, 0x65E572, 0xFFFFFF};
    private static final String[] COLOR_NAMES = {"Red", "Cyan", "Purple", "Orange", "Green", "White"};
    private static final int[] RANGES = {8, 16, 24, 32, 48, 64};
    private static final int[] TOLERANCES = {1, 2, 3, 4, 5, 7, 10};
    private static final int[] STEPS = {128, 192, 256, 384, 512, 768, 1024, 2048, 4096};

    private static final int[] MULTICOLOR_PALETTE = {
            0xFF0000, 0xFFFF00, 0x00FF00, 0x27D8FF, 0xB26BFF, 0xFFB347, 0xFFFFFF, 0x0000FF};
    private static final String[] MULTICOLOR_NAMES = {"Red", "Yellow", "Green", "Cyan", "Purple", "Orange", "White", "Blue"};

    private static Path file;
    public static boolean showAimPoints = true;
    public static boolean showEntities = true;
    public static boolean showFriendlyMobs = true;
    public static boolean showHostileMobs = true;
    public static boolean showPartiallyObscuredHitboxes = false;
    private static final java.util.Set<String> disabledEntityTypes = new java.util.HashSet<String>();
    public static boolean showSulfurTrajectory = true;
    public static int aimRange = 32;
    public static int maxHighlightedEntities = 4;
    public static int hitboxOpacity = 50;
    public static boolean multicolorHighlights = false;
    public static int multicolorSmoothness = 100;
    public static int multicolorNearColor = 0;
    public static int multicolorMiddleColor = 1;
    public static int multicolorFarColor = 2;
    public static int aimTolerance = 4;
    public static int trajectorySteps = 4096;
    public static int selectedColor = 0;
    // Default entity highlighting to red so it remains visible on the Sulfur
    // Cube's cyan absorbed-material texture.
    public static int otherColor = 0;
    public static int trajectoryColor = 2;

    // RGB overrides preserve existing palette-based configuration on upgrade.
    public static int selectedColorRgb = -1, otherColorRgb = -1, trajectoryColorRgb = -1;
    public static int multicolorNearRgb = -1, multicolorMiddleRgb = -1, multicolorFarRgb = -1;

    private BriefestBoxerConfig() {}

    public static void load(Path gameDirectory) {
        file = gameDirectory.resolve("config").resolve("briefest_boxer.properties");
        if (Files.isRegularFile(file)) {
            Properties values = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                values.load(in);
                showAimPoints = values.getProperty("showAimPoints", "true").equalsIgnoreCase("true");
                showEntities = values.getProperty("showEntities", "true").equalsIgnoreCase("true");
                showPartiallyObscuredHitboxes = values.getProperty("showPartiallyObscuredHitboxes", "false").equalsIgnoreCase("true");
                showFriendlyMobs = values.getProperty("showFriendlyMobs", "true").equalsIgnoreCase("true");
                showHostileMobs = values.getProperty("showHostileMobs", "true").equalsIgnoreCase("true");
                disabledEntityTypes.clear();
                for (String id : values.getProperty("disabledEntityTypes", "").split(",")) {
                    if (!id.trim().isEmpty()) disabledEntityTypes.add(id.trim());
                }
                showSulfurTrajectory = values.getProperty("showSulfurTrajectory", "true").equalsIgnoreCase("true");
                multicolorHighlights = values.getProperty("multicolorHighlights", "false").equalsIgnoreCase("true");
                try { multicolorSmoothness = Math.max(0, Math.min(100, Integer.parseInt(values.getProperty("multicolorSmoothness", "100")))); }
                catch (NumberFormatException ignored) { multicolorSmoothness = 100; }
                multicolorNearColor = multicolorIndex(values, "multicolorNearColor", 0);
                multicolorMiddleColor = multicolorIndex(values, "multicolorMiddleColor", 1);
                multicolorFarColor = multicolorIndex(values, "multicolorFarColor", 2);
                aimRange = valid(values, "aimRange", RANGES, 32);
                try { maxHighlightedEntities = Math.max(1, Math.min(64, Integer.parseInt(values.getProperty("maxHighlightedEntities", "4")))); }
                catch (NumberFormatException ignored) { maxHighlightedEntities = 4; }
                try { hitboxOpacity = Math.max(0, Math.min(100, Integer.parseInt(values.getProperty("hitboxOpacity", "50")))); }
                catch (NumberFormatException ignored) { hitboxOpacity = 50; }
                aimTolerance = valid(values, "aimTolerance", TOLERANCES, 4);
                trajectorySteps = valid(values, "trajectorySteps", STEPS, 4096);
                // Previous defaults often cut off long, low-friction Sulfur Cube launches.
                if (trajectorySteps == 48) trajectorySteps = 4096;
                selectedColorRgb = readRgb(values, "selectedColorRgb");
                otherColorRgb = readRgb(values, "otherColorRgb");
                trajectoryColorRgb = readRgb(values, "trajectoryColorRgb");
                multicolorNearRgb = readRgb(values, "multicolorNearRgb");
                multicolorMiddleRgb = readRgb(values, "multicolorMiddleRgb");
                multicolorFarRgb = readRgb(values, "multicolorFarRgb");
                selectedColor = colorIndex(values, "selectedColor", 0);
                otherColor = colorIndex(values, "otherColor", 0);
                trajectoryColor = colorIndex(values, "trajectoryColor", 2);
            } catch (IOException ignored) {
                // Defaults remain active when a user config cannot be read.
            }
        }
    }

    public static void save() {
        if (file == null) return;
        Properties values = new Properties();
        values.setProperty("showAimPoints", Boolean.toString(showAimPoints));
        values.setProperty("showEntities", Boolean.toString(showEntities));
        values.setProperty("showPartiallyObscuredHitboxes", Boolean.toString(showPartiallyObscuredHitboxes));
        values.setProperty("showFriendlyMobs", Boolean.toString(showFriendlyMobs));
        values.setProperty("showHostileMobs", Boolean.toString(showHostileMobs));
        java.util.List<String> disabled = new java.util.ArrayList<String>(disabledEntityTypes);
        java.util.Collections.sort(disabled);
        StringBuilder ids = new StringBuilder();
        for (String id : disabled) { if (ids.length() > 0) ids.append(','); ids.append(id); }
        values.setProperty("disabledEntityTypes", ids.toString());
        values.setProperty("showSulfurTrajectory", Boolean.toString(showSulfurTrajectory));
        values.setProperty("multicolorHighlights", Boolean.toString(multicolorHighlights));
        values.setProperty("multicolorSmoothness", Integer.toString(blendSmoothnessPercent()));
        values.setProperty("multicolorNearColor", Integer.toString(multicolorNearColor));
        values.setProperty("multicolorMiddleColor", Integer.toString(multicolorMiddleColor));
        values.setProperty("multicolorFarColor", Integer.toString(multicolorFarColor));
        values.setProperty("aimRange", Integer.toString(aimRange));
        values.setProperty("maxHighlightedEntities", Integer.toString(maxHighlightedEntities));
        values.setProperty("hitboxOpacity", Integer.toString(opacityPercent()));
        values.setProperty("aimTolerance", Integer.toString(aimTolerance));
        values.setProperty("trajectorySteps", Integer.toString(trajectorySteps));
        values.setProperty("selectedColorRgb", hexColor(selectedColor()));
        values.setProperty("otherColorRgb", hexColor(otherColor()));
        values.setProperty("trajectoryColorRgb", hexColor(trajectoryColor()));
        values.setProperty("multicolorNearRgb", hexColor(multicolorNearColor()));
        values.setProperty("multicolorMiddleRgb", hexColor(multicolorMiddleColor()));
        values.setProperty("multicolorFarRgb", hexColor(multicolorFarColor()));
        values.setProperty("selectedColor", Integer.toString(selectedColor));
        values.setProperty("otherColor", Integer.toString(otherColor));
        values.setProperty("trajectoryColor", Integer.toString(trajectoryColor));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                values.store(out, "Briefest Boxer client settings");
            }
        } catch (IOException ignored) {
            // A failed save should not stop the client from playing.
        }
    }

    public static boolean entityTypeEnabled(String id, boolean player, boolean hostile) {
        return (player ? showAimPoints : showEntities && (hostile ? showHostileMobs : showFriendlyMobs))
                && !disabledEntityTypes.contains(id);
    }
    public static void setEntityTypeEnabled(String id, boolean enabled) {
        if (enabled) disabledEntityTypes.remove(id); else disabledEntityTypes.add(id);
    }

    public static double aimRange() { return aimRange; }
    public static double minimumAimDot() { return Math.cos(Math.toRadians(aimTolerance)); }
    public static int selectedColor() { return rgbOr(selectedColorRgb, PALETTE[clampIndex(selectedColor)]); }
    public static int otherColor() { return rgbOr(otherColorRgb, PALETTE[clampIndex(otherColor)]); }
    public static int trajectoryColor() { return rgbOr(trajectoryColorRgb, PALETTE[clampIndex(trajectoryColor)]); }
    public static String colorName(int index) { return COLOR_NAMES[clampIndex(index)]; }
    public static int multicolorNearColor() { return rgbOr(multicolorNearRgb, multicolorRgb(multicolorNearColor)); }
    public static int multicolorMiddleColor() { return rgbOr(multicolorMiddleRgb, multicolorRgb(multicolorMiddleColor)); }
    public static int multicolorFarColor() { return rgbOr(multicolorFarRgb, multicolorRgb(multicolorFarColor)); }
    public static String hexColor(int rgb) { return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF); }
    private static int rgbOr(int override, int fallback) { return override >= 0 && override <= 0xFFFFFF ? override : fallback; }
    private static int readRgb(Properties values, String key) {
        String value = values.getProperty(key, "").trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9a-fA-F]{6}")) return -1;
        return Integer.parseInt(value, 16);
    }
    public static int multicolorRgb(int index) { return MULTICOLOR_PALETTE[clampMulticolorIndex(index)]; }
    public static String multicolorName(int index) { return MULTICOLOR_NAMES[clampMulticolorIndex(index)]; }
    public static int nextMulticolor(int index) { return (clampMulticolorIndex(index) + 1) % MULTICOLOR_PALETTE.length; }
    private static int clampMulticolorIndex(int index) { return Math.max(0, Math.min(MULTICOLOR_PALETTE.length - 1, index)); }
    private static int multicolorIndex(Properties values, String key, int fallback) {
        try {
            int index = Integer.parseInt(values.getProperty(key, Integer.toString(fallback)));
            return index >= 0 && index < MULTICOLOR_PALETTE.length ? index : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }
    public static int blendSmoothnessPercent() { return Math.max(0, Math.min(100, multicolorSmoothness)); }
    public static int opacityPercent() { return Math.max(0, Math.min(100, hitboxOpacity)); }
    public static int hitboxAlpha() { return (int) Math.round(opacityPercent() * 255.0 / 100.0); }
    public static int highlightLimit() { return Math.max(1, Math.min(64, maxHighlightedEntities)); }
    public static int[] entityLimits() { return new int[] {1, 2, 3, 5, 10, 20, 32, 64}; }
    public static int[] ranges() { return RANGES.clone(); }
    public static int[] tolerances() { return TOLERANCES.clone(); }
    public static int[] steps() { return STEPS.clone(); }

    private static int valid(Properties p, String key, int[] choices, int fallback) {
        int parsed;
        try { parsed = Integer.parseInt(p.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
        for (int choice : choices) if (choice == parsed) return parsed;
        return fallback;
    }
    private static int colorIndex(Properties p, String key, int fallback) {
        try { return clampIndex(Integer.parseInt(p.getProperty(key, Integer.toString(fallback)))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static int clampIndex(int index) { return Math.max(0, Math.min(PALETTE.length - 1, index)); }
}
