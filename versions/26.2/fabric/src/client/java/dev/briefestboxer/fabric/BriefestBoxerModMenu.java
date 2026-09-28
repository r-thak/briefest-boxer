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

        private BriefestBoxerConfigScreen(Screen parent) {
            super(Component.literal("Briefest Boxer Settings"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int x = width / 2 - 150;
            int y = height / 2 - 136;
            addRenderableWidget(button(x, y, 300, () -> "Player highlights: " + onOff(BriefestBoxerConfig.showAimPoints),
                    () -> BriefestBoxerConfig.showAimPoints = !BriefestBoxerConfig.showAimPoints));
            addRenderableWidget(button(x, y + 28, 300, () -> "Sulfur trajectory: " + onOff(BriefestBoxerConfig.showSulfurTrajectory),
                    () -> BriefestBoxerConfig.showSulfurTrajectory = !BriefestBoxerConfig.showSulfurTrajectory));
            addRenderableWidget(button(x, y + 56, 300, () -> "Other living entities: " + onOff(BriefestBoxerConfig.showEntities),
                    () -> BriefestBoxerConfig.showEntities = !BriefestBoxerConfig.showEntities));
            addRenderableWidget(button(x, y + 84, 300, () -> "Scan radius: " + BriefestBoxerConfig.aimRange + " blocks",
                    () -> BriefestBoxerConfig.aimRange = next(BriefestBoxerConfig.ranges(), BriefestBoxerConfig.aimRange)));
            addRenderableWidget(button(x, y + 112, 300, () -> "Trajectory steps: " + BriefestBoxerConfig.trajectorySteps,
                    () -> BriefestBoxerConfig.trajectorySteps = next(BriefestBoxerConfig.steps(), BriefestBoxerConfig.trajectorySteps)));
            addRenderableWidget(button(x, y + 140, 300, () -> "Highlight color: " + BriefestBoxerConfig.colorName(BriefestBoxerConfig.selectedColor),
                    () -> BriefestBoxerConfig.selectedColor = (BriefestBoxerConfig.selectedColor + 1) % 6));
            addRenderableWidget(button(x, y + 168, 300, () -> "Trajectory color: " + BriefestBoxerConfig.colorName(BriefestBoxerConfig.trajectoryColor),
                    () -> BriefestBoxerConfig.trajectoryColor = (BriefestBoxerConfig.trajectoryColor + 1) % 6));
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(width / 2 - 100, y + 196, 200, 20).build());
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
            graphics.centeredText(font, title, width / 2, height / 2 - 156, 0xFFFFFF);
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
