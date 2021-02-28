package dev.hephaestus.garden.impl;

import dev.hephaestus.garden.api.PlayerModVersionsContainer;

public interface PlayerVersionMap {
    PlayerModVersionsContainer getModVersions(String playerName);
}
