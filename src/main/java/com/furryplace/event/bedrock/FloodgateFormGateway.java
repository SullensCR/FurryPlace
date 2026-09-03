package com.furryplace.event.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Floodgate/Cumulus implementation loaded only when Floodgate is installed. */
public final class FloodgateFormGateway implements BedrockFormGateway {
    private final JavaPlugin plugin;

    public FloodgateFormGateway(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("floodgate");
    }

    @Override
    public boolean isBedrock(Player player) {
        return available() && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    @Override
    public boolean sendSimple(Player player, String title, String content, List<Button> buttons, Runnable closed) {
        if (!isBedrock(player)) return false;
        SimpleForm.Builder form = SimpleForm.builder().title(title).content(content)
            .validResultHandler(response -> {
                int selected = response.clickedButtonId();
                if (selected >= 0 && selected < buttons.size()) runSync(buttons.get(selected).action());
            })
            .closedOrInvalidResultHandler(() -> runSync(closed));
        buttons.forEach(button -> form.button(button.text()));
        return FloodgateApi.getInstance().sendForm(player.getUniqueId(), form.build());
    }

    @Override
    public boolean sendModal(Player player, String title, String content, String firstButton, Runnable firstAction,
                             String secondButton, Runnable secondAction, Runnable closed) {
        if (!isBedrock(player)) return false;
        return FloodgateApi.getInstance().sendForm(player.getUniqueId(), ModalForm.builder()
            .title(title)
            .content(content)
            .button1(firstButton)
            .button2(secondButton)
            .validResultHandler(response -> runSync(response.clickedFirst() ? firstAction : secondAction))
            .closedOrInvalidResultHandler(() -> runSync(closed))
            .build());
    }

    @Override
    public boolean sendInput(Player player, String title, String content, String label, String placeholder, String value,
                             Consumer<String> submitted, Runnable closed) {
        if (!isBedrock(player)) return false;
        return FloodgateApi.getInstance().sendForm(player.getUniqueId(), CustomForm.builder()
            .title(title)
            .label(content)
            .input(label, placeholder, value)
            .validResultHandler(response -> runSync(() -> submitted.accept(response.asInput())))
            .closedOrInvalidResultHandler(() -> runSync(closed))
            .build());
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
                .validResultHandler(response -> runSync(() -> result.accept(
                    "Botón seleccionado: " + response.getClickedButtonText())))
                .closedOrInvalidResultHandler(() -> runSync(() -> result.accept("El formulario fue cerrado.")))
                .build());
            case SIMPLE -> api.sendForm(uuid, SimpleForm.builder()
                .title("FurryPlace - Formulario Simple")
                .content("Selecciona una de las opciones para probar los botones Bedrock.")
                .button("Primera opción")
                .button("Segunda opción")
                .button("Cerrar")
                .validResultHandler(response -> runSync(() -> result.accept(
                    "Botón seleccionado: " + response.getClickedButton().getText())))
                .closedOrInvalidResultHandler(() -> runSync(() -> result.accept("El formulario fue cerrado.")))
                .build());
            case CUSTOM -> api.sendForm(uuid, CustomForm.builder()
                .title("FurryPlace - Formulario Custom")
                .label("Este formulario prueba los componentes de entrada de Bedrock.")
                .input("Texto", "Escribe algo", "Prueba")
                .dropdown("Lista", "Opción A", "Opción B", "Opción C")
                .toggle("Activar opción", true)
                .slider("Valor", 0, 100, 10, 50)
                .validResultHandler(response -> runSync(() -> result.accept("Campos enviados correctamente.")))
                .closedOrInvalidResultHandler(() -> runSync(() -> result.accept("El formulario fue cerrado.")))
                .build());
        };
    }

    private void runSync(Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
