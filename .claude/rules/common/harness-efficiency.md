# Harness Efficiency

> This file extends [agents.md](agents.md) with efficiency rules for agent/skill harnesses.

## Substance Gate for Learn / Learn-Eval

When `/learn` or `/learn-eval` is invoked, ask before writing any memory:

> Is this already obvious from the code or git history? Is it already in CLAUDE.md or project rules? Would another developer discover this independently within 5 minutes?

If yes to any, skip writing. Report: **"Nothing notable to memorize from this session."**

This prevents noise entries that clutter the memory index.

## Principle

Agent and skill harnesses are tools, not ceremonies. Every invocation should produce value — not just output. If a session has nothing novel to capture, say so briefly and move on. The cost of forced output is a diluted signal-to-noise ratio across all project documentation.
