package dev.briefestboxer.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.briefestboxer.core.BriefestBoxerConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class BriefestBoxerModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BriefestBoxerConfigScreen::new;
    }

    private static final class HitboxOpacitySlider extends AbstractSliderButton {
        private HitboxOpacitySlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), BriefestBoxerConfig.opacityPercent() / 100.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Hitbox opacity: " + Math.round(value * 100.0) + "%"));
        }

        @Override
        protected void applyValue() {
            BriefestBoxerConfig.hitboxOpacity = (int) Math.round(value * 100.0);
            BriefestBoxerConfig.save();
        }
    }

    private static final class BriefestBoxerConfigScreen extends Screen {
        private final Screen parent;
        private int headingY;
        private int limitLabelX;
        private int limitLabelY;
        private EditBox entityLimit;

        private BriefestBoxerConfigScreen(Screen parent) {
            super(Component.literal("Briefest Boxer Settings"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int buttonWidth = Math.min(300, Math.max(180, width - 24));
            int x = (width - buttonWidth) / 2;
            int rowGap = Math.max(20, Math.min(25, (height - 40 - 20) / 10));
            int groupHeight = rowGap * 10 + 20;
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
            limitLabelX = x;
            limitLabelY = y + rowGap * 8 + 6;
            entityLimit = new EditBox(font, x + buttonWidth - 52, y + rowGap * 8, 52, 20,
                    Component.literal("Max highlighted entities (1–64)"));
            entityLimit.setTextColor(0xFFFFFFFF);
            entityLimit.setTextColorUneditable(0xFF707070);
            entityLimit.setMaxLength(2);
            entityLimit.setValue(Integer.toString(BriefestBoxerConfig.highlightLimit()));
            entityLimit.setResponder(value -> {
                try {
                    int count = Integer.parseInt(value);
                    if (count < 1 || count > 64) throw new NumberFormatException();
                    entityLimit.setTextColor(0xFFFFFFFF);
                    BriefestBoxerConfig.maxHighlightedEntities = count;
                    BriefestBoxerConfig.save();
                } catch (NumberFormatException ignored) {
                    // Allow clearing the field while typing; invalid values
                    // never replace the last valid saved limit.
                    entityLimit.setTextColor(0xFFFF5555);
                }
            });
            addRenderableWidget(entityLimit);
            addRenderableWidget(new HitboxOpacitySlider(x, y + rowGap * 9, buttonWidth));
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(width / 2 - Math.min(100, buttonWidth / 2), y + rowGap * 10, Math.min(200, buttonWidth), 20).build());
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
            graphics.centeredText(font, title, width / 2, headingY, 0xFFFFFFFF);
            graphics.text(font, "Max entities (1–64)", limitLabelX, limitLabelY, 0xFFFFFFFF);
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
