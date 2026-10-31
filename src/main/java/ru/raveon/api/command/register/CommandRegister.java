package ru.raveon.api.command.register;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface CommandRegister {
    String name();

    String[] aliases() default {};

    String permission();
}
