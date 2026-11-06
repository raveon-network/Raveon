package ru.raveon.service.analyze;

import ru.raveon.model.CloudPunishments;

/** Conflict rule: a newer cloud revision wins; local-only edits use compare-and-swap. */
public record RuleSyncPlan(boolean upload, boolean conflict) {
    public static RuleSyncPlan decide(CloudPunishments cloud, int previousRevision,
            CloudPunishments.Policy local, CloudPunishments.Policy observedLocal) {
        if (cloud.mode().equals("off")) return new RuleSyncPlan(false, false);
        boolean differs = !local.equals(cloud.policy());
        if (cloud.mode().equals("upload")) return new RuleSyncPlan(differs, false);
        if (cloud.policy() == null) return new RuleSyncPlan(true, false);
        boolean localChanged = observedLocal != null && !local.equals(observedLocal);
        boolean conflict = localChanged && cloud.revision() != previousRevision && differs;
        return new RuleSyncPlan(localChanged && !conflict && differs, conflict);
    }
}
