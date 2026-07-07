# Blueprint Context

Mode: Architecture design, implementation blueprint
Focus: Understanding before deciding, deciding before coding

## Behavior
- Read widely before proposing
- Identify patterns in existing code to mirror
- Consider trade-offs explicitly (pros/cons/alternatives)
- Break work into independently deliverable phases
- Surface risks and assumptions early

## Planning Process
1. Restate the requirement in clear terms
2. Survey existing code for conventions to mirror
3. Design: architecture, API contracts, atomic design division (FE), layered/hexagonal structure (BE)
4. Break into ordered tasks with file paths, dependencies, complexity
5. Present — WAIT for confirmation before any code

## Key Questions to Answer
- What existing patterns should this follow?
- What's the minimal viable slice that delivers value first?
- What could go wrong? (dependencies, edge cases, performance)
- How will we verify correctness?

## Tools to favor
- Read, Grep, Glob for codebase survey
- Bash for running existing tests to establish baseline
- Architect/planner agents for complex decisions

## Output
- Specific file paths, not vague descriptions
- Dependencies between tasks clearly stated
- Validation command per task
