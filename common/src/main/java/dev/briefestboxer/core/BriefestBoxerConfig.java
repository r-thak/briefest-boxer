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

    private static Path file;
    public static boolean showAimPoints = true;
    public static boolean showEntities = true;
    public static boolean showSulfurTrajectory = true;
    public static int aimRange = 32;
    public static int maxHighlightedEntities = 4;
    public static int hitboxOpacity = 50;
    public static int aimTolerance = 4;
    public static int trajectorySteps = 4096;
    public static int selectedColor = 0;
    // Default entity highlighting to red so it remains visible on the Sulfur
    // Cube's cyan absorbed-material texture.
    public static int otherColor = 0;
    public static int trajectoryColor = 2;

    private BriefestBoxerConfig() {}

    public static void load(Path gameDirectory) {
        file = gameDirectory.resolve("config").resolve("briefest_boxer.properties");
        if (Files.isRegularFile(file)) {
            Properties values = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                values.load(in);
                showAimPoints = values.getProperty("showAimPoints", "true").equalsIgnoreCase("true");
                showEntities = values.getProperty("showEntities", "true").equalsIgnoreCase("true");
                showSulfurTrajectory = values.getProperty("showSulfurTrajectory", "true").equalsIgnoreCase("true");
                aimRange = valid(values, "aimRange", RANGES, 32);
                try { maxHighlightedEntities = Math.max(1, Math.min(64, Integer.parseInt(values.getProperty("maxHighlightedEntities", "4")))); }
                catch (NumberFormatException ignored) { maxHighlightedEntities = 4; }
                try { hitboxOpacity = Math.max(0, Math.min(100, Integer.parseInt(values.getProperty("hitboxOpacity", "50")))); }
                catch (NumberFormatException ignored) { hitboxOpacity = 50; }
                aimTolerance = valid(values, "aimTolerance", TOLERANCES, 4);
                trajectorySteps = valid(values, "trajectorySteps", STEPS, 4096);
                // Previous defaults often cut off long, low-friction Sulfur Cube launches.
                if (trajectorySteps == 48) trajectorySteps = 4096;
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
        values.setProperty("showSulfurTrajectory", Boolean.toString(showSulfurTrajectory));
        values.setProperty("aimRange", Integer.toString(aimRange));
        values.setProperty("maxHighlightedEntities", Integer.toString(maxHighlightedEntities));
        values.setProperty("hitboxOpacity", Integer.toString(opacityPercent()));
        values.setProperty("aimTolerance", Integer.toString(aimTolerance));
        values.setProperty("trajectorySteps", Integer.toString(trajectorySteps));
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

    public static double aimRange() { return aimRange; }
    public static double minimumAimDot() { return Math.cos(Math.toRadians(aimTolerance)); }
    public static int selectedColor() { return PALETTE[clampIndex(selectedColor)]; }
    public static int otherColor() { return PALETTE[clampIndex(otherColor)]; }
    public static int trajectoryColor() { return PALETTE[clampIndex(trajectoryColor)]; }
    public static String colorName(int index) { return COLOR_NAMES[clampIndex(index)]; }
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
