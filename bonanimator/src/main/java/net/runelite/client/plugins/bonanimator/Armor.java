package net.runelite.client.plugins.bonanimator;

import lombok.Getter;

@Getter
public enum Armor {
    BLACK("Black Armor"),
    MITHRIL("Mithril Armor"),
    ADAMANT("Adamant Armor"),
    RUNE("Rune Armor");

    private final String name;

    Armor(String name) {
        this.name = name;
    }
}