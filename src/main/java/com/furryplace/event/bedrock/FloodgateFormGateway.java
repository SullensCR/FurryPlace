package com.furryplace.event.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.function.Consumer;

/** Floodgate/Cumulus implementation loaded only when Floodgate is installed. */
public final class FloodgateFormGateway implements BedrockFormGateway {
    @Override
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("floodgate");
    }

    @Override
    public boolean isBedrock(Player player) {
        return available() && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    @Override
    public boolean sendTest(Player player, Mode mode, Consumer<String> result) {
        if (!isBedrock(player)) return false;
        UUID uuid = player.getUniqueId();
        FloodgateApi api = FloodgateApi.getInstance();
        return switch (mode) {
            case MODAL -> api.sendForm(uuid, ModalForm.builder()
                .title("FurryPlace - Formulario Modal")
                .content("Esta es una prueba de un formulario Modal de Bedrock.")
                .button1("Aceptar")
                .button2("Cancelar")
                .validResultHandler(response -> result.accept(
                    "Botón seleccionado: " + response.getClickedButtonText()))
                .closedOrInvalidResultHandler(() -> result.accept("El formulario fue cerrado."))
                .build());
            case SIMPLE -> api.sendForm(uuid, SimpleForm.builder()
                .title("FurryPlace - Formulario Simple")
                .content("Selecciona una de las opciones para probar los botones Bedrock.")
                .button("Primera opción")
                .button("Segunda opción")
                .button("Cerrar")
                .validResultHandler(response -> result.accept(
                    "Botón seleccionado: " + response.getClickedButton().getText()))
                .closedOrInvalidResultHandler(() -> result.accept("El formulario fue cerrado."))
                .build());
            case CUSTOM -> api.sendForm(uuid, CustomForm.builder()
                .title("FurryPlace - Formulario Custom")
                .label("Este formulario prueba los componentes de entrada de Bedrock.")
                .input("Texto", "Escribe algo", "Prueba")
                .dropdown("Lista", "Opción A", "Opción B", "Opción C")
                .toggle("Activar opción", true)
                .slider("Valor", 0, 100, 10, 50)
                .validResultHandler(response -> result.accept("Campos enviados correctamente."))
                .closedOrInvalidResultHandler(() -> result.accept("El formulario fue cerrado."))
                .build());
        };
    }
}
