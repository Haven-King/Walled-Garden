package dev.hephaestus.garden.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.VersionPredicate;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.lib.gson.JsonReader;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class Config {
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("walled-garden.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, ModDependency> REQUIRED_MODS = new LinkedHashMap<>();
    private static final Map<String, ModDependency> BLACKLISTED_MODS = new LinkedHashMap<>();
    private static final Map<String, ModDependency> WHITELISTED_MODS = new LinkedHashMap<>();

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
        } else {
            write();
        }
    }

    static void write() {
        try {
            JsonObject object = new JsonObject();

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

    static @Nullable ModDependency getRequiredVersion(String modId) {
        return REQUIRED_MODS.get(modId);
    }

    static @Nullable ModDependency getBlacklistedVersion(String modId) {
        return BLACKLISTED_MODS.get(modId);
    }

    static @Nullable ModDependency getWhitelistedVersion(String modId) {
        return WHITELISTED_MODS.get(modId);
    }

    static Collection<ModDependency> getRequiredMods() {
        return new ArrayList<>(REQUIRED_MODS.values());
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

        return result;
    }

    public static ModDependency unRequire(String modId) {
        return REQUIRED_MODS.remove(modId);
    }

    public static ModDependency unBlacklist(String modId) {
        return BLACKLISTED_MODS.remove(modId);
    }

    public static ModDependency unWhitelist(String modId) {
        return WHITELISTED_MODS.remove(modId);
    }
}
