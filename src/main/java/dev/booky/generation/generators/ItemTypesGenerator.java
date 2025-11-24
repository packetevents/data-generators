package dev.booky.generation.generators;
// Created by booky10 in MinecraftSource (19:02 05.09.23)

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.booky.generation.util.GenerationUtil;
import net.minecraft.Optionull;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.entity.FuelValues;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static dev.booky.generation.util.GenerationUtil.asFieldName;
import static net.minecraft.core.component.DataComponents.FOOD;
import static net.minecraft.core.component.DataComponents.JUKEBOX_PLAYABLE;
import static net.minecraft.core.component.DataComponents.MAX_DAMAGE;

public final class ItemTypesGenerator implements IGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final FuelValues VANILLA_FUEL_VALUES = FuelValues.vanillaBurnTimes(
            GenerationUtil.getVanillaRegistries(), FeatureFlags.REGISTRY.allFlags());

    @Override
    public void generate(Path outDir, String genName) throws IOException {
        Path genDir = outDir.resolve(genName);
        Files.createDirectories(genDir);

        Path inputPath = genDir.resolve("input.json");
        Path outputPath = genDir.resolve("output.txt");
        if (!Files.exists(inputPath)) {
            LOGGER.warn("Skipping generator, input path {} doesn't exist", inputPath);
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            // read inputs
            Set<Identifier> prevItems = GenerationUtil.loadJsonElement(inputPath, JsonObject.class).keySet().stream()
                    .map(String::toLowerCase).map(Identifier::parse).collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Identifier> items = BuiltInRegistries.ITEM.stream()
                    .map(BuiltInRegistries.ITEM::getKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // determine which items have been removed compared to input
            Set<Identifier> removedItems = new LinkedHashSet<>(prevItems);
            removedItems.removeAll(items);
            writer.write("// Removed items (");
            writer.write(SharedConstants.getCurrentVersion().name());
            writer.write("): ");
            writer.write(removedItems.toString());
            writer.newLine();

            // determine which items have been added compared to input
            Set<Identifier> addedItems = new LinkedHashSet<>(items);
            addedItems.removeAll(prevItems);
            writer.write("// Added items (");
            writer.write(SharedConstants.getCurrentVersion().name());
            writer.write("): ");
            writer.write(addedItems.toString());
            writer.newLine();

            // generate code for each added item
            for (Identifier addedItem : addedItems) {
                writer.newLine();
                writer.write("public static final ItemType ");
                writer.write(asFieldName(addedItem));
                writer.write(" = builder(\"");
                writer.write(GenerationUtil.toString(addedItem));
                writer.write("\")");

                Optional<Holder.Reference<Item>> itemHolder = BuiltInRegistries.ITEM.get(addedItem);
                if (itemHolder.isEmpty()) {
                    writer.write(".build(); // TODO: MISSING FROM REGISTRY");
                    continue;
                }

                Item item = itemHolder.get().value();
                DataComponentMap components = item.components(); // default components
                writer.write(".setMaxAmount(");
                writer.write(Integer.toString(item.getDefaultMaxStackSize()));
                writer.write(')');
                Integer maxDamage = components.get(MAX_DAMAGE);
                if (maxDamage != null) {
                    writer.write(".setMaxDurability(");
                    writer.write(Integer.toString(maxDamage));
                    writer.write(')');
                }
                if (item instanceof BlockItem blockItem) {
                    Identifier blockKey = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
                    String blockName = blockKey.getPath().toUpperCase(Locale.ROOT);
                    writer.write(".setPlacedType(StateTypes.");
                    writer.write(blockName);
                    writer.write(')');
                }

                String attributesStr = Arrays.stream(ItemAttribute.values())
                        .filter(attribute -> attribute.predicate.test(item))
                        .map(attribute -> "ItemAttribute." + attribute.name())
                        .collect(Collectors.joining(", "));
                if (!attributesStr.isBlank()) {
                    writer.write(".setAttributes(");
                    writer.write(attributesStr);
                    writer.write(')');
                }

                writer.write(".build();");
            }
        }
    }

    public enum ItemAttribute {

        MUSIC_DISC(item -> item.components().has(JUKEBOX_PLAYABLE)),
        EDIBLE(item -> item.components().has(FOOD)),
        FIRE_RESISTANT(item -> Optionull.mapOrDefault(item.components().get(DataComponents.DAMAGE_RESISTANT),
                resistant -> resistant.types().equals(DamageTypeTags.IS_FIRE), false)),
        WOOD_TIER("wood"),
        STONE_TIER("stone"),
        IRON_TIER("iron"),
        DIAMOND_TIER("diamond"),
        GOLD_TIER("gold"),
        NETHERITE_TIER("netherite"),
        FUEL(item -> VANILLA_FUEL_VALUES.fuelItems().contains(item)),
        SWORD("sword"),
        SHOVEL(item -> item instanceof ShovelItem),
        AXE(item -> item instanceof AxeItem),
        PICKAXE("pickaxe"),
        HOE(item -> item instanceof HoeItem);

        private final Predicate<Item> predicate;

        ItemAttribute(String keyword) {
            this(item -> BuiltInRegistries.ITEM.getKey(item).getPath().contains(keyword));
        }

        ItemAttribute(Predicate<Item> predicate) {
            this.predicate = predicate;
        }
    }
}
