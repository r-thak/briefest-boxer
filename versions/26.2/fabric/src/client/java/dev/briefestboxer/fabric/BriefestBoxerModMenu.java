package dev.briefestboxer.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.briefestboxer.core.BriefestBoxerConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class BriefestBoxerModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BriefestBoxerConfigScreen::new;
    }

    private static Button colorButton(Screen parent, int x, int y, int width, String name,
            java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
        return Button.builder(Component.literal(name + ": " + BriefestBoxerConfig.hexColor(get.getAsInt())),
                button -> net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                        new ColorPickerScreen(parent, name, get.getAsInt(), set)))
                .bounds(x, y, width, 20).build();
    }

    private static final class ColorPickerScreen extends Screen {
        private final Screen parent;
        private final java.util.function.IntConsumer apply;
        private int rgb;
        private int panelX, panelY, panelWidth;
        private EditBox hex;
        private Button done;
        private boolean syncing;
        private final ChannelSlider[] channels = new ChannelSlider[3];

        private ColorPickerScreen(Screen parent, String name, int initial, java.util.function.IntConsumer apply) {
            super(Component.literal(name + " color"));
            this.parent = parent;
            this.rgb = initial & 0xFFFFFF;
            this.apply = apply;
        }

        @Override
        protected void init() {
            panelWidth = Math.min(300, Math.max(180, width - 24));
            panelX = (width - panelWidth) / 2;
            panelY = Math.max(20, (height - 175) / 2);
            String[] names = {"Red", "Green", "Blue"};
            for (int i = 0; i < 3; i++) {
                channels[i] = new ChannelSlider(panelX, panelY + 20 + i * 25, panelWidth, names[i], 16 - i * 8);
                addRenderableWidget(channels[i]);
            }
            hex = new EditBox(font, panelX + 45, panelY + 100, panelWidth - 45, 20,
                    Component.literal("RGB hex color, #RRGGBB"));
            hex.setMaxLength(7);
            hex.setTextColor(0xFFFFFFFF);
            hex.setValue(BriefestBoxerConfig.hexColor(rgb));
            hex.setResponder(value -> {
                if (syncing) return;
                String digits = value.startsWith("#") ? value.substring(1) : value;
                boolean valid = digits.matches("[0-9a-fA-F]{6}");
                hex.setTextColor(valid ? 0xFFFFFFFF : 0xFFFF5555);
                if (done != null) done.active = valid;
                if (valid) {
                    rgb = Integer.parseInt(digits, 16);
                    for (ChannelSlider channel : channels) channel.sync();
                }
            });
            addRenderableWidget(hex);
            done = Button.builder(Component.literal("Done"), button -> {
                apply.accept(rgb);
                BriefestBoxerConfig.save();
                onClose();
            }).bounds(panelX, panelY + 150, (panelWidth - 4) / 2, 20).build();
            addRenderableWidget(done);
            addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                    .bounds(panelX + (panelWidth + 4) / 2, panelY + 150, (panelWidth - 4) / 2, 20).build());
        }

        private void updateHex() {
            syncing = true;
            hex.setValue(BriefestBoxerConfig.hexColor(rgb));
            hex.setTextColor(0xFFFFFFFF);
            done.active = true;
            syncing = false;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(font, title, width / 2, panelY, 0xFFFFFFFF);
            graphics.text(font, "Hex", panelX, panelY + 106, 0xFFFFFFFF);
            graphics.fill(panelX, panelY + 127, panelX + panelWidth, panelY + 144, 0xFF000000 | rgb);
            graphics.outline(panelX, panelY + 127, panelWidth, 17, 0xFFFFFFFF);
        }

        @Override
        public void onClose() { minecraft.setScreenAndShow(parent); }

        private final class ChannelSlider extends AbstractSliderButton {
            private final String name;
            private final int shift;
            private ChannelSlider(int x, int y, int width, String name, int shift) {
                super(x, y, width, 20, Component.empty(), ((rgb >>> shift) & 255) / 255.0);
                this.name = name;
                this.shift = shift;
                updateMessage();
            }
            private void sync() {
                value = ((rgb >>> shift) & 255) / 255.0;
                updateMessage();
            }
            @Override
            protected void updateMessage() { setMessage(Component.literal(name + ": " + Math.round(value * 255.0))); }
            @Override
            protected void applyValue() {
                int channel = (int) Math.round(value * 255.0);
                rgb = (rgb & ~(255 << shift)) | (channel << shift);
                updateHex();
            }
        }
    }

    private static final class HitboxOpacitySlider extends AbstractSliderButton {
        private HitboxOpacitySlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), BriefestBoxerConfig.opacityPercent() / 100.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Opacity: " + Math.round(value * 100.0) + "%"));
        }

        @Override
        protected void applyValue() {
            BriefestBoxerConfig.hitboxOpacity = (int) Math.round(value * 100.0);
            BriefestBoxerConfig.save();
        }
    }

    private static final class MulticolorSmoothnessSlider extends AbstractSliderButton {
        private MulticolorSmoothnessSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), BriefestBoxerConfig.blendSmoothnessPercent() / 100.0);
            setTooltip(Tooltip.create(Component.literal(
                    "Multicolor blending smoothness: 0% = distinct color bands; 100% = broad, smooth transitions.")));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Blend: " + Math.round(value * 100.0) + "%"));
        }

        @Override
        protected void applyValue() {
            BriefestBoxerConfig.multicolorSmoothness = (int) Math.round(value * 100.0);
            BriefestBoxerConfig.save();
        }
    }

    private static final class MulticolorPaletteScreen extends Screen {
        private final Screen parent;
        private int headingY;

        private MulticolorPaletteScreen(Screen parent) {
            super(Component.literal("Multicolor highlight colors"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int w = Math.min(300, Math.max(180, width - 24));
            int x = (width - w) / 2, y = Math.max(28, height / 2 - 50);
            headingY = y - 20;
            addSelector(x, y, w, "Nearest", BriefestBoxerConfig::multicolorNearColor,
                    value -> BriefestBoxerConfig.multicolorNearRgb = value);
            addSelector(x, y + 25, w, "Middle", BriefestBoxerConfig::multicolorMiddleColor,
                    value -> BriefestBoxerConfig.multicolorMiddleRgb = value);
            addSelector(x, y + 50, w, "Furthest", BriefestBoxerConfig::multicolorFarColor,
                    value -> BriefestBoxerConfig.multicolorFarRgb = value);
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(x, y + 85, w, 20).build());
        }

        private void addSelector(int x, int y, int w, String name,
                java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
            addRenderableWidget(colorButton(this, x, y, w, name, get, set));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(font, title, width / 2, headingY, 0xFFFFFFFF);
        }

        @Override
        public void onClose() {
            BriefestBoxerConfig.save();
            minecraft.setScreenAndShow(parent);
        }
    }

    private static final class EntityChecklistScreen extends Screen {
        private final Screen parent;
        private final java.util.List<net.minecraft.world.entity.EntityType<?>> types = EntityHighlightFilters.livingTypes();
        private int page;
        private String query = "";
        private int pages;
        private int listX, listWidth;

        private EntityChecklistScreen(Screen parent) {
            super(Component.literal("Highlighted entities"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            listWidth = Math.min(420, width - 24);
            listX = (width - listWidth) / 2;
            String[] names = {"Players", "Friendly mobs", "Hostile mobs"};
            int groupWidth = (listWidth - 8) / 3;
            for (int group = 0; group < 3; group++) {
                final int selectedGroup = group;
                long total = types.stream().filter(type -> EntityHighlightFilters.group(type) == selectedGroup).count();
                long enabled = types.stream().filter(type -> EntityHighlightFilters.group(type) == selectedGroup
                        && EntityHighlightFilters.enabled(type)).count();
                String label = names[group] + (enabled > 0 && enabled < total ? " (some)" : "");
                addRenderableWidget(Checkbox.builder(Component.literal(label), font)
                        .pos(listX + group * (groupWidth + 4), 34).maxWidth(groupWidth)
                        .selected(total > 0 && enabled == total)
                        .onValueChange((box, value) -> {
                            EntityHighlightFilters.setGroup(selectedGroup, value, types);
                            BriefestBoxerConfig.save();
                            rebuildWidgets();
                        }).build());
            }
            EditBox search = new EditBox(font, listX, 61, listWidth, 20, Component.literal("Search entity types"));
            search.setTextColor(0xFFFFFFFF);
            search.setHint(Component.literal("Search entities…"));
            search.setValue(query);
            search.setResponder(value -> {
                query = value;
                page = 0;
                rebuildWidgets();
                // Keep typing in the recreated search field.
                for (var child : children()) if (child instanceof EditBox edit) {
                    setFocused(edit);
                    edit.setCursorPosition(edit.getValue().length());
                    break;
                }
            });
            addRenderableWidget(search);
            String needle = query.toLowerCase(java.util.Locale.ROOT);
            var filtered = types.stream().filter(type -> type.getDescription().getString().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || EntityHighlightFilters.id(type).toLowerCase(java.util.Locale.ROOT).contains(needle)).toList();
            int rows = Math.max(1, (height - 145) / 23);
            int capacity = rows * 2;
            pages = Math.max(1, (filtered.size() + capacity - 1) / capacity);
            page = Math.min(page, pages - 1);
            int columnWidth = (listWidth - 8) / 2;
            for (int index = page * capacity; index < Math.min(filtered.size(), (page + 1) * capacity); index++) {
                var type = filtered.get(index);
                int offset = index - page * capacity;
                addRenderableWidget(Checkbox.builder(type.getDescription(), font)
                        .pos(listX + (offset % 2) * (columnWidth + 8), 90 + (offset / 2) * 23)
                        .maxWidth(columnWidth).selected(EntityHighlightFilters.enabled(type))
                        .onValueChange((box, value) -> {
                            EntityHighlightFilters.setType(type, value);
                            BriefestBoxerConfig.save();
                            rebuildWidgets();
                        }).build());
            }
            Button previous = Button.builder(Component.literal("Previous"), button -> { page--; rebuildWidgets(); })
                    .bounds(listX, height - 49, 80, 20).build();
            previous.active = page > 0;
            addRenderableWidget(previous);
            Button next = Button.builder(Component.literal("Next"), button -> { page++; rebuildWidgets(); })
                    .bounds(listX + listWidth - 80, height - 49, 80, 20).build();
            next.active = page + 1 < pages;
            addRenderableWidget(next);
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(width / 2 - 75, height - 25, 150, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
            graphics.centeredText(font, (page + 1) + " / " + pages, width / 2, height - 43, 0xFFFFFFFF);
        }

        @Override
        public void onClose() {
            BriefestBoxerConfig.save();
            minecraft.setScreenAndShow(parent);
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
            addRenderableWidget(Button.builder(Component.literal("Entity checklist…"),
                    button -> minecraft.setScreenAndShow(new EntityChecklistScreen(this)))
                    .bounds(x, y, buttonWidth, 20).build());
            addRenderableWidget(button(x, y + rowGap, buttonWidth, () -> "Sulfur trajectory: " + onOff(BriefestBoxerConfig.showSulfurTrajectory),
                    () -> BriefestBoxerConfig.showSulfurTrajectory = !BriefestBoxerConfig.showSulfurTrajectory));
            addRenderableWidget(button(x, y + rowGap * 2, (buttonWidth - 4) / 2, () -> "Mobs: " + onOff(BriefestBoxerConfig.showEntities),
                    () -> BriefestBoxerConfig.showEntities = !BriefestBoxerConfig.showEntities));
            Button partialCover = button(x + (buttonWidth + 4) / 2, y + rowGap * 2, (buttonWidth - 4) / 2,
                    () -> "Partial cover: " + onOff(BriefestBoxerConfig.showPartiallyObscuredHitboxes),
                    () -> BriefestBoxerConfig.showPartiallyObscuredHitboxes = !BriefestBoxerConfig.showPartiallyObscuredHitboxes);
            partialCover.setTooltip(Tooltip.create(Component.literal(
                    "Show the full reachable highlight when an entity is partially obscured. Fully hidden entities remain hidden.")));
            addRenderableWidget(partialCover);
            addRenderableWidget(button(x, y + rowGap * 3, buttonWidth, () -> "Scan radius: " + BriefestBoxerConfig.aimRange + " blocks",
                    () -> BriefestBoxerConfig.aimRange = next(BriefestBoxerConfig.ranges(), BriefestBoxerConfig.aimRange)));
            addRenderableWidget(button(x, y + rowGap * 4, buttonWidth, () -> "Trajectory steps: " + BriefestBoxerConfig.trajectorySteps,
                    () -> BriefestBoxerConfig.trajectorySteps = next(BriefestBoxerConfig.steps(), BriefestBoxerConfig.trajectorySteps)));
            addRenderableWidget(colorButton(this, x, y + rowGap * 5, (buttonWidth - 4) / 2, "Player",
                    BriefestBoxerConfig::selectedColor, value -> BriefestBoxerConfig.selectedColorRgb = value));
            addRenderableWidget(button(x + (buttonWidth + 4) / 2, y + rowGap * 5, (buttonWidth - 4) / 2,
                    () -> "Multicolor: " + onOff(BriefestBoxerConfig.multicolorHighlights),
                    () -> BriefestBoxerConfig.multicolorHighlights = !BriefestBoxerConfig.multicolorHighlights));
            addRenderableWidget(colorButton(this, x, y + rowGap * 6, buttonWidth, "Trajectory",
                    BriefestBoxerConfig::trajectoryColor, value -> BriefestBoxerConfig.trajectoryColorRgb = value));
            addRenderableWidget(colorButton(this, x, y + rowGap * 7, (buttonWidth - 4) / 2, "Entity",
                    BriefestBoxerConfig::otherColor, value -> BriefestBoxerConfig.otherColorRgb = value));
            addRenderableWidget(Button.builder(Component.literal("Gradient colors…"),
                    button -> minecraft.setScreenAndShow(new MulticolorPaletteScreen(this)))
                    .bounds(x + (buttonWidth + 4) / 2, y + rowGap * 7, (buttonWidth - 4) / 2, 20).build());
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
            addRenderableWidget(new HitboxOpacitySlider(x, y + rowGap * 9, (buttonWidth - 4) / 2));
            addRenderableWidget(new MulticolorSmoothnessSlider(x + (buttonWidth + 4) / 2,
                    y + rowGap * 9, (buttonWidth - 4) / 2));
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
