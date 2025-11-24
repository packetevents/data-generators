package dev.booky.generation.generators;
// Created by booky10 in MinecraftSource (19:02 05.09.23)

import com.google.common.base.Preconditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.booky.generation.util.GenerationUtil;
import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.tags.VanillaBlockTagsProvider;
import net.minecraft.data.tags.VanillaItemTagsProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class TagsGenerator implements IGenerator {

    private void runDataGenerator(Path outDir) throws IOException {
        WorldVersion version = SharedConstants.getCurrentVersion();
        DataGenerator generator = new DataGenerator(outDir, version, true);

        DataGenerator.PackGenerator vanilla = generator.getVanillaPack(true);

        CompletableFuture<HolderLookup.Provider> vanillaRegistryFuture = CompletableFuture.supplyAsync(
                VanillaRegistries::createLookup, Util.backgroundExecutor());
        vanilla.addProvider(output ->
                new VanillaBlockTagsProvider(output, vanillaRegistryFuture));
        vanilla.addProvider(output ->
                new VanillaItemTagsProvider(output, vanillaRegistryFuture));

        generator.run();
    }

    private static List<Path> searchDirs(Path startDir, String dirName) throws IOException {
        List<Path> tagDirs = new ArrayList<>();
        Files.walkFileTree(startDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc == null && dirName.equals(dir.getFileName().toString())) {
                    tagDirs.add(dir); // only add matching dirs
                }
                return super.postVisitDirectory(dir, exc);
            }
        });
        return Collections.unmodifiableList(tagDirs);
    }

    private static String buildTagRef(TagType tagType, Identifier tagName) {
        return buildRef(tagType.tagsClass(), tagName);
    }

    private static String buildTypeRef(TagType tagType, Identifier tagName) {
        return buildRef(tagType.typesClass(), tagName);
    }

    private static String buildRef(String className, Identifier tagName) {
        return className + '.' + GenerationUtil.asFieldName(tagName);
    }

    @Override
    public void generate(Path outDir, String genName) throws IOException {
        Path genOutDir = outDir.resolve(genName);

        // run data generators to extract vanilla tags
        Path vanillaDataDir = genOutDir.resolve("vanilla");
        this.runDataGenerator(vanillaDataDir);

        // search extract vanilla data for tag directories
        // TODO support non-"minecraft" namespaced tags
        List<Path> tagDirs = searchDirs(vanillaDataDir, "tags");

        // build info data for available tag types - PacketEvents only supports blocks/items at the moment
        List<TagType> tagTypes = List.of(
                new TagType(Identifier.withDefaultNamespace("block"), "BlockTags", "StateTypes", BlockTags.class),
                new TagType(Identifier.withDefaultNamespace("item"), "ItemTags", "ItemTypes", ItemTags.class)
        );

        // the content of this map is used for copying the tag content from
        // another tag in code - this is a very simple structure currently,
        // forward references are simple not checked
        Map<TagContent, String> copyRefs = new HashMap<>();

        for (TagType tagType : tagTypes) {
            // look at order of mc fields, this is required for the tags
            // to have consistent ordering
            //
            // the paths of the specific tag are populated later
            Map<Identifier, List<Path>> tagPaths = new LinkedHashMap<>();
            for (Field field : tagType.mcClass().getFields()) {
                if (!Modifier.isPublic(field.getModifiers())
                        || !Modifier.isStatic(field.getModifiers())
                        || !Modifier.isFinal(field.getModifiers())) {
                    continue;
                }

                try {
                    TagKey<?> key = (TagKey<?>) field.get(null);
                    tagPaths.put(key.location(), new ArrayList<>());
                } catch (IllegalAccessException exception) {
                    throw new RuntimeException(exception);
                }
            }

            // populate tag paths with data generated content
            for (Path tagRootDir : tagDirs) {
                Path tagDir = tagRootDir.resolve(tagType.registryName().getPath()); // TODO
                if (!Files.isDirectory(tagDir)) {
                    continue; // doesn't exist
                }

                // walk tag dir, nested tags are allowed in minecraft
                try (Stream<Path> tree = Files.walk(tagDir)) {
                    tree.filter(Files::isRegularFile).forEach(path -> {
                        String tagPath = tagDir.relativize(path).toString();
                        tagPath = tagPath.substring(0, tagPath.length() - ".json".length());
                        Identifier tagName = Identifier.parse(tagPath);
                        tagPaths.get(tagName).add(path); // add path to tag
                    });
                }
            }

            // accumulate tags from every result
            Map<Identifier, Tag> tagObjs = new LinkedHashMap<>();
            for (Map.Entry<Identifier, List<Path>> entry : tagPaths.entrySet()) {
                // read values from every file
                JsonArray values = new JsonArray();
                for (Path path : entry.getValue()) {
                    JsonObject tagContents = GenerationUtil.loadJsonElement(path, JsonObject.class);
                    values.addAll(tagContents.remove("values").getAsJsonArray());
                    Preconditions.checkState(tagContents.isEmpty(), "%s != empty", tagContents);
                }

                // build tag from read values
                TagContent content = new TagContent();
                for (JsonElement elem : values) {
                    content.add(elem.getAsString());
                }
                Tag tag = new Tag(tagType, entry.getKey(), content);
                tagObjs.put(tag.name, tag);
            }

            // build parent structure, required for correct ordering
            for (Map.Entry<Identifier, Tag> entry : tagObjs.entrySet()) {
                for (Identifier tag : entry.getValue().content.tags()) {
                    entry.getValue().parents.add(tagObjs.get(tag));
                }
            }

            // open output path for writing down tag code
            Path outPath = genOutDir.resolve(GenerationUtil.toString(tagType.registryName()) + ".txt");
            try (BufferedWriter writer = Files.newBufferedWriter(outPath)) {
                // this ensures everything gets written in correct order by
                // first looping through all tags with no parents,
                // then removing the processed tags as a parent from everywhere
                // until every tag has been processed
                while (!tagObjs.isEmpty()) {
                    List<Tag> roots = tagObjs.values().stream()
                            .filter(tag -> tag.parents.isEmpty())
                            .toList();
                    if (roots.isEmpty()) {
                        // when tags are still present and everyone still has
                        // unprocessed parents, some sort of self-loop is present
                        // just throw errors, this can't be recovered
                        throw new IllegalStateException("Self-loop in remaining tags detected: " + tagObjs.keySet());
                    }

                    // finally, output the current set of roots
                    for (Tag root : roots) {
                        writer.write(root.asString(copyRefs));
                        writer.newLine();
                    }

                    // remove roots from everywhere - they have been successfully processed
                    tagObjs.values().removeAll(roots);
                    for (Tag tag : tagObjs.values()) {
                        tag.parents.removeAll(roots);
                    }
                }
            }
        }
    }

    // represents a type of tags supported by PacketEvents
    private record TagType(
            Identifier registryName,
            String tagsClass,
            String typesClass,
            Class<?> mcClass
    ) {
    }

    private record TagContent(List<Identifier> tags, List<Identifier> types) {

        public TagContent() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        public void add(String string) {
            if (string.indexOf('#') == 0) { // tag identifier
                this.tags.add(Identifier.parse(string.substring(1)));
            } else { // normal type entry
                this.types.add(Identifier.parse(string));
            }
        }

        public boolean isEmpty() {
            return this.types.isEmpty() && this.tags.isEmpty();
        }
    }

    private static final class Tag {

        private final TagType tagType;
        private final Identifier name;
        private final TagContent content;

        // used for sorting tags to counter forward references
        private final List<Tag> parents = new ArrayList<>();

        private Tag(TagType tagType, Identifier name, TagContent content) {
            this.tagType = tagType;
            this.name = name;
            this.content = content;
        }

        public String asString(Map<TagContent, String> copyRefs) {
            // build common self reference string
            String selfRef = buildTagRef(this.tagType, this.name);

            // prevent compile errors if empty
            if (this.content.isEmpty()) {
                return "copy(null, " + selfRef + ");";
            }

            // first check if this tag can just be copied
            String copyRef = copyRefs.get(this.content);
            if (copyRef != null) {
                return "copy(" + copyRef + ", " + selfRef + ");";
            }
            // register as a reference for copying
            // (not the best place for this, but it works and I don't care)
            copyRefs.put(this.content, selfRef);

            // not able to copy - build tag string
            StringBuilder builder = new StringBuilder(selfRef);
            for (Identifier tag : this.content.tags()) {
                builder.append(".addTag(").append(buildTagRef(this.tagType, tag)).append(')');
            }
            if (!this.content.types().isEmpty()) {
                String joined = this.content.types().stream()
                        .map(entry -> buildTypeRef(this.tagType, entry))
                        .collect(Collectors.joining(", "));
                builder.append(".add(").append(joined).append(')');
            }
            return builder.append(';').toString();
        }
    }
}
