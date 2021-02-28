package dev.hephaestus.garden.impl;

import dev.hephaestus.garden.api.PlayerModVersionsContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class PlayerModVersionsContainerImpl implements PlayerModVersionsContainer {
    private final Map<String, String> versions = new HashMap<>();

    @Override
    public @Nullable String getVersion(String modId) {
        return this.versions.get(modId);
    }

    public void put(String modId, String version) {
        this.versions.put(modId, version);
    }

    @NotNull
    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        return this.versions.entrySet().iterator();
    }
}
