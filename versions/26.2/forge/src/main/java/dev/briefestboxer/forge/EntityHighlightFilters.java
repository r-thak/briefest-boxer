package dev.briefestboxer.forge;

import dev.briefestboxer.core.BriefestBoxerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import java.util.ArrayList;
import java.util.List;

/** Registry-based filtering; no entity creation or per-frame registry scans. */
final class EntityHighlightFilters {
    private EntityHighlightFilters() {}
    static String id(EntityType<?> type) { return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(); }
    static int group(EntityType<?> type) {
        return id(type).equals("minecraft:player") ? 0 : type.getCategory() == MobCategory.MONSTER ? 2 : 1;
    }
    static boolean enabled(EntityType<?> type) {
        int group = group(type);
        return BriefestBoxerConfig.entityTypeEnabled(id(type), group == 0, group == 2);
    }
    static List<EntityType<?>> livingTypes() {
        List<EntityType<?>> types = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (DefaultAttributes.hasSupplier(type) || id(type).equals("minecraft:player")) types.add(type);
        }
        types.sort(java.util.Comparator.comparing(type -> type.getDescription().getString()));
        return types;
    }
    static void setGroup(int group, boolean enabled, List<EntityType<?>> types) {
        if (group == 0) BriefestBoxerConfig.showAimPoints = enabled;
        else {
            if (enabled) BriefestBoxerConfig.showEntities = true;
            if (group == 1) BriefestBoxerConfig.showFriendlyMobs = enabled;
            else BriefestBoxerConfig.showHostileMobs = enabled;
        }
        for (EntityType<?> type : types) {
            if (group(type) == group) BriefestBoxerConfig.setEntityTypeEnabled(id(type), enabled);
        }
    }
    static void setType(EntityType<?> type, boolean enabled) {
        if (enabled) {
            int group = group(type);
            if (group == 0) BriefestBoxerConfig.showAimPoints = true;
            else {
                BriefestBoxerConfig.showEntities = true;
                if (group == 1) BriefestBoxerConfig.showFriendlyMobs = true;
                else BriefestBoxerConfig.showHostileMobs = true;
            }
        }
        BriefestBoxerConfig.setEntityTypeEnabled(id(type), enabled);
    }
}
