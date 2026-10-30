package ru.raveon.command;

import lombok.RequiredArgsConstructor;

import org.bukkit.plugin.Plugin;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;

import ru.raveon.Raveon;
import ru.raveon.api.command.register.CommandRegister;
import ru.raveon.command.handler.BaseCommandExecutor;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

@RequiredArgsConstructor
public class CommandManager {
    private final Plugin plugin;
    private final Map<String, BaseCommandExecutor> commands;

    public CommandManager(Plugin plugin) {
        this.plugin = plugin;
        this.commands = new HashMap<>();
    }

    private Set<Class<?>> findAnnotatedClasses() {
        Reflections reflections =
                new Reflections(
                        new ConfigurationBuilder()
                                .forPackages("ru.raveon.command.commands")
                                .setScanners(Scanners.TypesAnnotated));

        return reflections.getTypesAnnotatedWith(CommandRegister.class);
    }

    public void registerCommands() {
        Set<Class<?>> commandsClasses = findAnnotatedClasses();

        for (Class<?> commandClass : commandsClasses) {
            try {
                if (!commandClass.isAnnotationPresent(CommandRegister.class)) {
                    plugin.getLogger()
                            .log(
                                    Level.WARNING,
                                    Raveon.INSTANCE.getLanguageService().plain("logs.command-missing-annotation", "class", commandClass.getName()));
                    continue;
                }

                if (!BaseCommandExecutor.class.isAssignableFrom(commandClass)) {
                    plugin.getLogger()
                            .log(
                                    Level.WARNING,
                                    Raveon.INSTANCE.getLanguageService().plain("logs.command-invalid-class", "class", commandClass.getName()));
                    continue;
                }

                @SuppressWarnings("unchecked")
                Class<? extends BaseCommandExecutor> executorClass =
                        (Class<? extends BaseCommandExecutor>) commandClass;

                CommandRegister annotation = commandClass.getAnnotation(CommandRegister.class);
                String commandName = annotation.name();

                Constructor<? extends BaseCommandExecutor> constructor =
                        executorClass.getDeclaredConstructor();
                BaseCommandExecutor command = constructor.newInstance();

                commands.put(commandName, command);

                plugin.getLogger()
                        .log(
                                Level.INFO,
                                Raveon.INSTANCE.getLanguageService().plain("logs.command-registered", "command", commandName));

            } catch (Exception e) {
                plugin.getLogger()
                        .log(
                                Level.SEVERE,
                                Raveon.INSTANCE.getLanguageService().plain("logs.command-failed", "class", commandClass.getName()),
                                e);
            }
        }

        plugin.getLogger().log(Level.INFO, Raveon.INSTANCE.getLanguageService().plain("logs.commands-registered", "count", commands.size()));
    }

    public void registerWrappers() {
        commands.values().forEach(BaseCommandExecutor::registerWrappers);
    }

}
