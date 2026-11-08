# Model buffers

Each model has a complete buffer block at `analyze.models.pro.buffer` or
`analyze.models.ultra.buffer` in `checks.yml`.

```yaml
analyze:
  models:
    pro:
      buffer:
        source: local
        inherit: ""
        flag: 50.0
        reset: 25.0
        multiplier: 100.0
        decrease: 0.25
        threshold: 0.85
        legit-threshold: 0.1
        weight: 1.0
        primary-multiplier: 2.0
```

`source: website` is the default: a complete API buffer replaces this local block.
`source: local` selects this model's local block even when the API supplies a policy.
Without an API buffer the local block is the fallback in either mode. This switch
does not grant access to a model; local `enabled` and action switches still only
narrow the API profile. Roles and mitigation weight keep their existing precedence.

Apply local edits with `/raveon reload`; website profile updates are polled automatically.
An existing configuration is not overwritten. Legacy `analyze.buffer.*`,
`analyze.models.<id>.probability` and `inherit-buffer` remain fallbacks for missing
new keys. New per-model values take precedence over their legacy counterparts.
To use local mode in an existing file, add the buffer block to that model.

## Shared buffers

`inherit: ultra` on Pro makes Pro feed Ultra's buffer. The owner supplies:

- `threshold`, `legit-threshold`;
- `flag`, `reset`, `multiplier`, `decrease`.

The answer source supplies `weight`, and its `primary-multiplier` if it is the
primary model. Source action switches remain independent; mitigation scoring
continues to use the source's mitigation settings. A local source may therefore
feed a website-controlled owner, and vice versa; each model's source is resolved
before inheritance.

For probability `p`, owner thresholds `t` and `l`, effective source weight `w`:

- `p > t`: add `(p - t) * owner.multiplier * w`;
- `p < l`: subtract `owner.decrease * w`, bounded below by zero;
- otherwise: no change.

A growing buffer strictly above the owner's `flag` produces a flag and resets to
the owner's `reset`. Reloading or changing policy retains accumulated buffers.
Self-inheritance, chains and cycles are rejected. A disabled or unlicensed owner
falls back to the source's own buffer without enabling the owner.

This changes the probability thresholds used by inherited buffers compared with
2.0.8. Review a shared buffer's owner thresholds when updating the plugin.
