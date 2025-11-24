package dev.booky.generation.util;
// Created by booky10 in PacketEventsUtils (17:42 20.12.23)

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class GenerationUtil {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    public static HolderLookup.Provider VANILLA_REGISTRIES = VanillaRegistries.createLookup();
    public static RegistryAccess VANILLA_REGISTRY_ACCESS = null;

    private GenerationUtil() {
    }

    public static HolderLookup.Provider getVanillaRegistries() {
        return VANILLA_REGISTRIES;
    }

    public static JsonElement loadJsonElement(Path path) throws IOException {
        return loadJsonElement(path, JsonElement.class);
    }

    public static <T> T loadJsonElement(Path path, Class<T> typeClass) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, typeClass);
        }
    }

    public static void saveJsonElement(JsonElement element, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            GSON.toJson(element, writer);
        }
    }

    @SuppressWarnings("unchecked") // this works
    public static String getRegistryName(Registry<?> registry) {
        Identifier registryKey = ((Registry<Registry<?>>) BuiltInRegistries.REGISTRY).getKey(registry);
        if (registryKey == null) {
            throw new IllegalStateException("Can't get name of unregistered registry: " + registry);
        }
        return toString(registryKey);
    }

    public static String asFieldName(Identifier location) {
        return toString(location)
                .toUpperCase(Locale.ROOT)
                .replace(File.separatorChar, '_') // remove nesting
                .replace('.', '_') // remove dots
                .replaceAll("__+", "_"); // remove adjacent underscores
    }

    public static String toString(Identifier resourceLoc) {
        if (Identifier.DEFAULT_NAMESPACE.equals(resourceLoc.getNamespace())) {
            return resourceLoc.getPath();
        }
        return resourceLoc.toString();
    }
}
