package dev.briefestboxer.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.briefestboxer.core.BriefestBoxerConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class BriefestBoxerModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BriefestBoxerConfigScreen::new;
    }

    private static final class BriefestBoxerConfigScreen extends Screen {
        private final Screen parent;
        private int headingY;

        private BriefestBoxerConfigScreen(Screen parent) {
            super(Component.literal("Briefest Boxer Settings"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int buttonWidth = Math.min(300, Math.max(180, width - 24));
            int x = (width - buttonWidth) / 2;
            int rowGap = Math.max(20, Math.min(25, (height - 40 - 20) / 8));
            int groupHeight = rowGap * 8 + 20;
            int y = Math.max(20, (height - groupHeight) / 2);
            headingY = y - 14;
            addRenderableWidget(button(x, y, buttonWidth, () -> "Player highlights: " + onOff(BriefestBoxerConfig.showAimPoints),
                    () -> BriefestBoxerConfig.showAimPoints = !BriefestBoxerConfig.showAimPoints));
            addRenderableWidget(button(x, y + rowGap, buttonWidth, () -> "Sulfur trajectory: " + onOff(BriefestBoxerConfig.showSulfurTrajectory),
                    () -> BriefestBoxerConfig.showSulfurTrajectory = !BriefestBoxerConfig.showSulfurTrajectory));
            addRenderableWidget(button(x, y + rowGap * 2, buttonWidth, () -> "Other living entities: " + onOff(BriefestBoxerConfig.showEntities),
                    () -> BriefestBoxerConfig.showEntities = !BriefestBoxerConfig.showEntities));
            addRenderableWidget(button(x, y + rowGap * 3, buttonWidth, () -> "Scan radius: " + BriefestBoxerConfig.aimRange + " blocks",
                    () -> BriefestBoxerConfig.aimRange = next(BriefestBoxerConfig.ranges(), BriefestBoxerConfig.aimRange)));
            addRenderableWidget(button(x, y + rowGap * 4, buttonWidth, () -> "Trajectory steps: " + BriefestBoxerConfig.trajectorySteps,
                    () -> BriefestBoxerConfig.trajectorySteps = next(BriefestBoxerConfig.steps(), BriefestBoxerConfig.trajectorySteps)));
            addRenderableWidget(button(x, y + rowGap * 5, buttonWidth, () -> "Highlight color: " + BriefestBoxerConfig.colorName(BriefestBoxerConfig.selectedColor),
                    () -> BriefestBoxerConfig.selectedColor = (BriefestBoxerConfig.selectedColor + 1) % 6));
            addRenderableWidget(button(x, y + rowGap * 6, buttonWidth, () -> "Trajectory color: " + BriefestBoxerConfig.colorName(BriefestBoxerConfig.trajectoryColor),
                    () -> BriefestBoxerConfig.trajectoryColor = (BriefestBoxerConfig.trajectoryColor + 1) % 6));
            addRenderableWidget(button(x, y + rowGap * 7, buttonWidth, () -> "Other entity color: " + BriefestBoxerConfig.colorName(BriefestBoxerConfig.otherColor),
                    () -> BriefestBoxerConfig.otherColor = (BriefestBoxerConfig.otherColor + 1) % 6));
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(width / 2 - Math.min(100, buttonWidth / 2), y + rowGap * 8, Math.min(200, buttonWidth), 20).build());
        }

        private Button button(int x, int y, int w, java.util.function.Supplier<String> label, Runnable action) {
            return Button.builder(Component.literal(label.get()), button -> {
                action.run();
                button.setMessage(Component.literal(label.get()));
                BriefestBoxerConfig.save();
            }).bounds(x, y, w, 20).build();
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(font, title, width / 2, headingY, 0xFFFFFF);
        }

        @Override
        public void onClose() {
            BriefestBoxerConfig.save();
            minecraft.setScreenAndShow(parent);
        }

        private static String onOff(boolean value) { return value ? "On" : "Off"; }
        private static int next(int[] choices, int current) {
            for (int i = 0; i < choices.length; i++) if (choices[i] == current) return choices[(i + 1) % choices.length];
            return choices[0];
        }
    }
}
