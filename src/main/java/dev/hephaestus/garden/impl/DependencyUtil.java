package dev.hephaestus.garden.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.version.VersionInterval;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

public class DependencyUtil {
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
        Collection<VersionPredicate> predicates = modDependency.getVersionRequirements();

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

    static void readDependenciesContainer(JsonReader reader, Map<String, ModDependency> modDependencies) throws IOException, JsonParseException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw parseException("Dependency container must be an object! Error was located at: ", reader);
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

    private static @Nullable ModDependency dependency(String modId, JsonReader reader) throws IOException, JsonParseException {
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
                            throw parseException("Dependency version range array must only contain string values! Error was located at: ", reader);
                        }

                        matcherStringList.add(reader.nextString());
                    }

                    reader.endArray();
                    break;
                default:
                    throw parseException("Dependency version range must be a string or string array! Error was located at: ", reader);
            }

        try {
            if (matcherStringList.isEmpty()) {
                return null;
            }

            final Collection<VersionPredicate> ranges = VersionPredicate.parse(matcherStringList);

            return new ModDependency() {
                @Override
                public String getModId() {
                    return modId;
                }

                @Override
                public Collection<VersionPredicate> getVersionRequirements() {
                    return ranges;
                }

                @Override
                public boolean matches(Version version) {
                    for (VersionPredicate predicate : getVersionRequirements()) {
                        if (predicate.test(version)) return true;
                    }

                    return false;
                }

                @Override
                public List<VersionInterval> getVersionIntervals() {
                    List<VersionInterval> ret = Collections.emptyList();

                    for (VersionPredicate predicate : getVersionRequirements()) {
                        ret = VersionInterval.or(ret, predicate.getInterval());
                    }

                    return ret;
                }

                @Override
                public Kind getKind() {
                    return Kind.DEPENDS;
                }

                @Override
                public String toString() {
                    final StringBuilder builder = new StringBuilder("{");
                    builder.append(getKind().getKey());
                    builder.append(' ');
                    builder.append(getModId());
                    builder.append(" @ [");

                    for (int i = 0; i < matcherStringList.size(); i++) {
                        if (i > 0) {
                            builder.append(" || ");
                        }

                        builder.append(matcherStringList.get(i));
                    }

                    builder.append("]}");
                    return builder.toString();
                }
            };
        } catch (VersionParsingException ignored) {
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

        return String.format("%s %s", modName, dependency);
    }

    static Text getTextWithLinks(Map<String, String> missingMods) {
        MutableText text = Text.literal("");

        FabricLoader loader = FabricLoader.getInstance();

        for (Map.Entry<String, String> entry : missingMods.entrySet()) {
            text.append("\n");

            loader.getModContainer(entry.getKey()).ifPresent(modContainer -> {
                text.append(Text.literal(modContainer.getMetadata().getName() + " "));
                /* Man, I wish this worked, but the disconnect screen doesn't display links :(
                Map<String, String> contact = modContainer.getMetadata().getContact().asMap();

                if (contact.containsKey("homepage")) {
                    dependencyText.styled(style -> style.withClickEvent(
                            new ClickEvent(ClickEvent.Action.OPEN_URL, contact.get("homepage")))
                    );
                }*/
            });

            text.append(Text.literal(entry.getValue()));
        }

        return text;
    }

    static JsonParseException parseException(String message, JsonReader reader) {
        return new JsonParseException(message + reader.toString().substring(reader.getClass().getSimpleName().length()));
    }
}
