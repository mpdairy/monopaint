# Architecture rules

Before implementing any feature:

1. Search for analogous functionality already in the repository.
2. Identify the abstraction that currently owns this responsibility.
3. Prefer extending that abstraction over creating parallel code.
4. Do not duplicate logic merely to avoid modifying existing code.
5. If two code paths would become substantially similar, refactor shared behavior first.
6. Preserve package/layer boundaries described in DESIGN.md.
7. Before finishing, review the diff specifically for:
   - duplicated logic
   - new helpers duplicating existing helpers
   - bypassed abstractions
   - special cases that belong in the core model
   - feature-specific state that should be generalized
