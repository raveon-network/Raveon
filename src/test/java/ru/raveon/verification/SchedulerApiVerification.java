package ru.raveon.verification;

import ru.raveon.utils.SchedulerUtils;

public final class SchedulerApiVerification {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("modern"))
            Class.forName("ru.raveon.utils.SchedulerUtils$Folia");
        else if (SchedulerUtils.isFolia())
            throw new AssertionError("Legacy Paper API unexpectedly identifies as Folia");
        System.out.println("Scheduler API linkage verification passed");
    }
}
