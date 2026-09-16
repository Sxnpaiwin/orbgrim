package ac.grim.grimac.command.commands;

import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.command.OlympiaGuiBridge;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.NotNull;

/**
 * Olympia log GUI entry points: {@code /olympia [player]} and {@code /grim logs [player]}.
 * No target opens the recent-offenders overview; a name jumps straight to that player.
 * Console receives an error — the GUI needs an in-game viewer (use the export files or
 * {@code /grim history} from console instead).
 */
public class OlympiaLogs implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("olympia")
                        .literal("logs", Description.of("Open the Olympia flag log GUI"))
                        .optional("target", StringParser.stringParser())
                        .permission("olympia.logs")
                        .handler(this::handle)
        );
        commandManager.command(
                commandManager.commandBuilder("olympia")
                        .permission("olympia.logs")
                        .handler(this::handle)
        );
        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("logs", Description.of("Open the Olympia flag log GUI"))
                        .optional("target", StringParser.stringParser())
                        .permission("olympia.logs")
                        .handler(this::handle)
        );
    }

    private void handle(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        if (!sender.isPlayer()) {
            sender.sendMessage(Component.text("The log GUI needs an in-game viewer.", NamedTextColor.RED));
            return;
        }
        String target = context.getOrDefault("target", null);
        if (!OlympiaGuiBridge.open(sender, target)) {
            sender.sendMessage(Component.text("The log GUI is not available on this platform.", NamedTextColor.RED));
        }
    }
}
