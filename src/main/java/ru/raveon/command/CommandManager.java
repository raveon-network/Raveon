package ru.raveon.command;

import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.Plugin;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import ru.raveon.api.command.register.CommandRegister;
import ru.raveon.command.handler.BaseCommandExecutor;

import java.lang.reflect.Constructor;
import java.util.Collections;
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
        Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                        .forPackages("ru.raveon.command.commands")
                        .setScanners(Scanners.TypesAnnotated)
        );

        return reflections.getTypesAnnotatedWith(CommandRegister.class);
    }

    public void registerCommands() {
        Set<Class<?>> commandsClasses = findAnnotatedClasses();

        for (Class<?> commandClass : commandsClasses) {
            try {
                if (!commandClass.isAnnotationPresent(CommandRegister.class)) {
                    plugin.getLogger().log(Level.WARNING, "Класс " + commandClass.getName() + " не имеет аннотации @CommandRegister");
                    continue;
                }

                if (!BaseCommandExecutor.class.isAssignableFrom(commandClass)) {
                    plugin.getLogger().log(Level.WARNING, "Класс " + commandClass.getName() + " не наследует BaseCommandExecutor");
                    continue;
                }

                @SuppressWarnings("unchecked")
                Class<? extends BaseCommandExecutor> executorClass = (Class<? extends BaseCommandExecutor>) commandClass;

                CommandRegister annotation = commandClass.getAnnotation(CommandRegister.class);
                String commandName = annotation.name();

                Constructor<? extends BaseCommandExecutor> constructor = executorClass.getDeclaredConstructor();
                BaseCommandExecutor command = constructor.newInstance();

                commands.put(commandName, command);

                plugin.getLogger().log(Level.INFO, "Команда '%s' успешно зарегистрирована".formatted(commandName));

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при регистрации команды " + commandClass.getName(), e);
            }
        }

        plugin.getLogger().log(Level.INFO, "Всего зарегистрировано команд: " + commands.size());
    }

    public void registerWrappers() {
        commands.values().forEach(BaseCommandExecutor::registerWrappers);
    }

    public Map<String, BaseCommandExecutor> getCommands() {
        return Collections.unmodifiableMap(commands);
    }

    public BaseCommandExecutor getCommand(String name) {
        return commands.get(name);
    }
}