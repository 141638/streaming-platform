---
description: Angular frontend implementation — scaffold, build features, components, services, forms, routing, and styling.
argument-hint: "<task description> — e.g., 'create a user profile component', 'add a checkout route with guards'"
---

# /angular-dev — Angular Frontend Implementation

**TOOL ROUTING:** When this command is invoked, you MUST:
1. Call `Skill({skill: "angular-developer"})` to load the Angular reference knowledge.
2. Delegate implementation work to `Agent({subagent_type: "angular-developer", description: "<short task description>"})`. Do NOT attempt Angular implementation inline — the agent enforces project conventions, runs `ng build` verification, and applies design-quality standards.

**Before routing**, record telemetry:
`node .claude/scripts/telemetry-track.mjs --command angular-dev`

## When to Use

- Creating or scaffolding Angular components, services, directives, pipes, guards, or resolvers
- Implementing frontend features from a blueprint or plan
- Adding routing, lazy loading, route guards, or data resolvers
- Building forms (signal forms preferred for new development)
- Implementing reactive state with Angular Signals
- Styling components with CSS custom properties or Tailwind
- Adding animations or ARIA accessibility patterns
- Any Angular-specific implementation task

## What It Does

The **angular-developer** agent:

1. **Survey** — Reads existing patterns in the codebase to mirror conventions
2. **Scaffold** — Uses `ng generate` for all new artifacts (never hand-creates boilerplate)
3. **Implement** — Writes idiomatic Angular code following project standards
4. **Verify** — Runs `ng build` to ensure zero errors (mandatory, per skill Rule 3)
5. **Report** — Summarizes files created/modified and build result

## Examples

```
/angular-dev Create a UserProfile component with avatar, bio, and edit button
/angular-dev Add a checkout route with auth guard and lazy loading
/angular-dev Build a signal-based search form with debounced input
/angular-dev Create an admin dashboard with bento-grid layout
/angular-dev Implement route transition animations for the settings pages
```

## Related

- `/implement --fe` — flexible implementation with auto-detected mode (delegates to angular-developer for frontend tasks)
- `/review` — code review for Angular changes (uses typescript-reviewer agent)
- `/blueprint` — plan before implementing complex Angular features
