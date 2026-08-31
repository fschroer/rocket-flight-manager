---
description: Commit uncommitted Steam Pigeon work across all three repos, coordinating cross-repo changes
---

**The definition of this command lives in the Locator repo, not here.** Read and follow:

```
C:\STM32_Projects\Locator\.claude\commands\sp-commit.md
```

Do **not** copy its contents into this file. It encodes rules the Steam Pigeon project
learned the hard way, and two copies that can drift apart is the problem this pointer
exists to avoid — the commands were moved out of `~/.claude/commands/` for the same
reason, so that a rule lives with the code rather than with one machine.

**It walks all three repos and reports through `Scripts/sp-status.sh`, so run it from the Locator repo rather than from here.**

(Absolute path assumes the standard local layout; on a fresh clone elsewhere, open the
Locator repo and read `.claude/commands/` there.)
