package ru.raveon.api.command.register;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface SubCommandRegister {
    String permission();

    String[] aliases();
}
