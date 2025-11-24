package dev.booky.generation.generators;
// Created by booky10 in PacketEventsGenerators (23:59 18.10.2024)

import com.google.gson.JsonObject;
import dev.booky.generation.util.GenerationUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ItemDataGenerator implements IGenerator {

    @Override
    public void generate(Path outDir, String genName) throws IOException {
        // save most common values as default
        Map<DataComponentType<?>, Object2IntMap<Object>> allCommonValues = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            item.components().forEach(component -> {
                Object2IntMap<Object> counts = allCommonValues.computeIfAbsent(component.type(),
                        $ -> new Object2IntLinkedOpenHashMap<>());
                counts.computeInt(component.value(), ($, i) ->
                        Objects.requireNonNullElse(i, 0) + 1);
            });
        }
        // remove common values which are not shared by every item
        for (Item item : BuiltInRegistries.ITEM) {
            allCommonValues.keySet().removeIf(type -> !item.components().has(type));
        }
        Map<DataComponentType<?>, Object> commonValues = allCommonValues.entrySet().stream()
                .filter(entry -> entry.getValue().values().intStream().max().orElse(0) > 1)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().keySet().stream()
                        .max(Comparator.comparingInt(entry.getValue()::getInt)).orElseThrow()))
                .sorted(Comparator.comparing(entry -> BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (entry1, entry2) -> {
                            throw new IllegalArgumentException(entry1 + " " + entry2);
                        }, LinkedHashMap::new));
        commonValues.forEach((type, counts) -> System.out.println(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type) + " -> " + counts));

        JsonObject defObj = new JsonObject();
        for (Map.Entry<DataComponentType<?>, Object> entry : commonValues.entrySet()) {
            this.encode((DataComponentType<? super Object>) entry.getKey(), entry.getValue(), defObj);
        }

        JsonObject obj = new JsonObject();
        Identifier defKey = Identifier.fromNamespaceAndPath("packetevents", "default");
        obj.add(GenerationUtil.toString(defKey), defObj);
        for (Item item : BuiltInRegistries.ITEM.stream()
                .sorted(Comparator.comparing(Item::getDescriptionId))
                .toList()) {
            JsonObject dataObj = new JsonObject();
            item.components().stream()
                    .filter(component -> !component.value().equals(commonValues.get(component.type()))) // don't repeat defaults
                    .sorted(Comparator.comparing(component -> BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type())))
                    .forEach(component -> this.encode(component, dataObj));
            if (dataObj.isEmpty()) {
                continue;
            }
            Identifier itemKey = BuiltInRegistries.ITEM.getKey(item);
            obj.add(GenerationUtil.toString(itemKey), dataObj);
        }
        GenerationUtil.saveJsonElement(obj, outDir.resolve(genName + ".json"));
    }

    private <T> void encode(TypedDataComponent<T> typedComponent, JsonObject obj) {
        this.encode(typedComponent.type(), typedComponent.value(), obj);
    }

    // encode base component data using base64
    private <T> void encode(DataComponentType<T> type, T value, JsonObject obj) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), GenerationUtil.VANILLA_REGISTRY_ACCESS);
        try {
            type.streamCodec().encode(buf, value);

            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            String string = Base64.getEncoder().encodeToString(bytes);

            Identifier typeKey = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            obj.addProperty(GenerationUtil.toString(typeKey), string);
        } finally {
            buf.release();
        }
    }
}
