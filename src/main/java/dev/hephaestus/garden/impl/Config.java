package dev.hephaestus.garden.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.VersionPredicate;
import net.fabricmc.loader.api.metadata.ModDependency;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Config {
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("walled-garden.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean REQUIRE_INSTALLED_MODS_WITH_BLOCKS_AND_ITEMS = true;
    private static final Map<String, ModDependency> REQUIRED_MODS = new LinkedHashMap<>();
    private static final Map<String, ModDependency> BLACKLISTED_MODS = new LinkedHashMap<>();
    private static final Map<String, ModDependency> WHITELISTED_MODS = new LinkedHashMap<>();
    private static final Map<String, ModDependency> MODS_THAT_ADD_BLOCKS_AND_ITEMS = new LinkedHashMap<>();

    private Config() {
    }

    static void read() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                JsonReader reader = new JsonReader(Files.newBufferedReader(CONFIG_FILE));

                reader.beginObject();

                while (reader.hasNext()) {
                    String key = reader.nextName();

                    switch (key) {
                        case "require_mods_that_add_blocks_and_items":
                            REQUIRE_INSTALLED_MODS_WITH_BLOCKS_AND_ITEMS = reader.nextBoolean();
                            break;
                        case "required":
                            DependencyUtil.readDependenciesContainer(reader, REQUIRED_MODS);
                            break;
                        case "blacklisted":
                            DependencyUtil.readDependenciesContainer(reader, BLACKLISTED_MODS);
                            break;
                        case "whitelisted":
                            DependencyUtil.readDependenciesContainer(reader, WHITELISTED_MODS);
                            break;
                        default:
                            reader.skipValue();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        write();
    }

    static void write() {
        try {
            JsonObject object = new JsonObject();

            object.addProperty("require_mods_that_add_blocks_and_items", REQUIRE_INSTALLED_MODS_WITH_BLOCKS_AND_ITEMS);
            object.add("required", DependencyUtil.toJsonObject(REQUIRED_MODS));
            object.add("blacklisted", DependencyUtil.toJsonObject(BLACKLISTED_MODS));
            object.add("whitelisted", DependencyUtil.toJsonObject(WHITELISTED_MODS));

            BufferedWriter writer = Files.newBufferedWriter(CONFIG_FILE);
            GSON.toJson(object, writer);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void require(String modId, ModDependency dependency) {
        REQUIRED_MODS.put(modId, dependency);
        write();
    }

    static void blacklist(String modId, ModDependency dependency) {
        BLACKLISTED_MODS.put(modId, dependency);
        write();
    }

    static void whitelist(String modId, ModDependency dependency) {
        WHITELISTED_MODS.put(modId, dependency);
        write();
    }

    static void addsBlockOrItem(String modId) {
        MODS_THAT_ADD_BLOCKS_AND_ITEMS.put(modId, DependencyUtil.dependency(modId, "\"*\""));
    }

    static @Nullable ModDependency getRequiredVersion(String modId) {
        return REQUIRE_INSTALLED_MODS_WITH_BLOCKS_AND_ITEMS && MODS_THAT_ADD_BLOCKS_AND_ITEMS.containsKey(modId)
                ? MODS_THAT_ADD_BLOCKS_AND_ITEMS.get(modId)
                : REQUIRED_MODS.get(modId);
    }

    static @Nullable ModDependency getBlacklistedVersion(String modId) {
        return BLACKLISTED_MODS.get(modId);
    }

    static @Nullable ModDependency getWhitelistedVersion(String modId) {
        return WHITELISTED_MODS.get(modId);
    }

    static Collection<ModDependency> getRequiredMods() {
        Collection<ModDependency> dependencies = new ArrayList<>(REQUIRED_MODS.values());

        if (REQUIRE_INSTALLED_MODS_WITH_BLOCKS_AND_ITEMS) {
            dependencies.addAll(MODS_THAT_ADD_BLOCKS_AND_ITEMS.values());
        }

        return dependencies;
    }

    static Collection<ModDependency> getBlacklistedMods() {
        return new ArrayList<>(BLACKLISTED_MODS.values());
    }

    static Collection<ModDependency> getWhitelistedMods() {
        return new ArrayList<>(WHITELISTED_MODS.values());
    }

    static Map<String, String> getMissing(Map<String, String> mods) {
        Map<String, String> result = new LinkedHashMap<>();

        for (Map.Entry<String, ModDependency> entry : REQUIRED_MODS.entrySet()) {
            if (!mods.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue().toString());
                continue;
            }

            try {
                if (!entry.getValue().matches(SemanticVersion.parse(mods.get(entry.getKey())))) {
                    result.put(entry.getKey(), entry.getValue().toString());
                }
            } catch (VersionParsingException e) {
                for (VersionPredicate predicate : entry.getValue().getVersionRequirements()) {
                    if (predicate.getType() != VersionPredicate.Type.ANY) {
                        result.put(entry.getKey(), entry.getValue().toString());
                        break;
                    }
                }
            }
        }

        if (REQUIRE_INSTALLED_MODS_WITH_BLOCKS_AND_ITEMS) {
            for (Map.Entry<String, ModDependency> entry : MODS_THAT_ADD_BLOCKS_AND_ITEMS.entrySet()) {
                if (!mods.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), entry.getValue().toString());
                }
            }
        }

        return result;
    }

    public static ModDependency unRequire(String modId) {
        ModDependency result = REQUIRED_MODS.remove(modId);

        write();

        return result;
    }

    public static ModDependency unBlacklist(String modId) {
        ModDependency result = BLACKLISTED_MODS.remove(modId);

        write();

        return result;
    }

    public static ModDependency unWhitelist(String modId) {
        ModDependency result = WHITELISTED_MODS.remove(modId);

        write();

        return result;
    }

    public static void setRequireModsThatAddBlocksAndItems(Boolean required) {
        REQUIRE_INSTALLED_MODS_WITH_BLOCKS_AND_ITEMS = required;
    }
}
