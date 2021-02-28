package dev.hephaestus.garden.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.VersionPredicate;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.lib.gson.JsonReader;
import net.fabricmc.loader.lib.gson.JsonToken;
import net.fabricmc.loader.metadata.ParseMetadataException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class DependencyUtil {
    private static final Constructor<? extends ModDependency> MOD_DEPENDENCY_CONSTRUCTOR;

    static {
        Constructor<? extends ModDependency> MOD_DEPENDENCY_CONSTRUCTOR1 = null;

        try {
            //noinspection unchecked
            Class<? extends ModDependency> clazz = (Class<? extends ModDependency>) Class.forName("net.fabricmc.loader.metadata.ModDependencyImpl");
            MOD_DEPENDENCY_CONSTRUCTOR1 = clazz.getDeclaredConstructor(String.class, List.class);
            MOD_DEPENDENCY_CONSTRUCTOR1.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            e.printStackTrace();
        }

        MOD_DEPENDENCY_CONSTRUCTOR = MOD_DEPENDENCY_CONSTRUCTOR1;
    }

    static JsonObject toJsonObject(Map<String, ModDependency> map) {
        JsonObject object = new JsonObject();

        for (Map.Entry<String, ModDependency> entry : map.entrySet()) {
            JsonElement element = toJsonElement(entry.getValue());

            if (element != null) {
                object.add(entry.getKey(), element);
            }
        }

        return object;
    }

    private static @Nullable JsonElement toJsonElement(ModDependency modDependency) {
        Set<VersionPredicate> predicates = modDependency.getVersionRequirements();

        if (predicates.size() == 1) {
            return new JsonPrimitive(predicates.iterator().next().toString());
        } else if (predicates.size() > 1) {
            JsonArray array = new JsonArray();

            for (VersionPredicate predicate : predicates) {
                array.add(predicate.toString());
            }

            return array;
        }

        return null;
    }

    static void readDependenciesContainer(JsonReader reader, Map<String, ModDependency> modDependencies) throws IOException, ParseMetadataException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw new ParseMetadataException("Dependency container must be an object!", reader);
        }

        reader.beginObject();

        try {
            while (reader.hasNext()) {
                final String modId = reader.nextName();
                modDependencies.put(modId, dependency(modId, reader));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        reader.endObject();
    }

    private static @Nullable ModDependency dependency(String modId, JsonReader reader) throws IOException, ParseMetadataException {
            final List<String> matcherStringList = new ArrayList<>();

            FabricLoader loader = FabricLoader.getInstance();

            switch (reader.peek()) {
                case STRING:
                    String string = reader.nextString();

                    if (string.equals(".")) {
                        Optional<ModContainer> optional = loader.getModContainer(modId);
                        if (!optional.isPresent()) throw new RuntimeException(String.format("Mod %s is not installed on the server!", modId));

                        matcherStringList.add(optional.get().getMetadata().getVersion().toString());
                    } else {
                        matcherStringList.add(string);
                    }

                    break;
                case BEGIN_ARRAY:
                    reader.beginArray();

                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.STRING) {
                            throw new ParseMetadataException("Dependency version range array must only contain string values", reader);
                        }

                        matcherStringList.add(reader.nextString());
                    }

                    reader.endArray();
                    break;
                default:
                    throw new ParseMetadataException("Dependency version range must be a string or string array!", reader);
            }

        try {
            return matcherStringList.isEmpty() ? null : MOD_DEPENDENCY_CONSTRUCTOR.newInstance(modId, matcherStringList);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    static @Nullable ModDependency dependency(String modId, String modDependency) {
        try {
            JsonReader reader = new JsonReader(new StringReader(modDependency));
            return dependency(modId, reader);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String toString(ModDependency dependency) {
        String modName = FabricLoader.getInstance().getModContainer(dependency.getModId())
                .map(c -> c.getMetadata().getName()).orElse(dependency.getModId());

        return dependency.toString().replace(dependency.getModId(), modName);
    }
}
