package com.furryplace.event.menu;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockMenuProjectionTest {
    @Test
    void convertsFormattedNamesAndLoreIntoReadableBedrockButtonText() {
        String name = BedrockMenuProjection.plain(MiniMessage.miniMessage().deserialize("<gold>Parcela <player></gold>",
            Placeholder.unparsed("player", "Luna")));
        String text = BedrockMenuProjection.buttonText(name, List.of("Parcela 7", "", "Votos: 3"));

        assertEquals("Parcela Luna\nParcela 7\nVotos: 3", text);
    }

    @Test
    void selectsOnlyTheRequestedDynamicPage() {
        List<String> entries = List.of("uno", "dos", "tres", "cuatro", "cinco");

        assertEquals(List.of("uno", "dos"), BedrockMenuProjection.pageEntries(entries, 0, 2));
        assertEquals(List.of("tres", "cuatro"), BedrockMenuProjection.pageEntries(entries, 1, 2));
        assertEquals(List.of("cinco"), BedrockMenuProjection.pageEntries(entries, 2, 2));
        assertEquals(List.of(), BedrockMenuProjection.pageEntries(entries, 3, 2));
    }
}
