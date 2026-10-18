# Testing Policy

## Rule: Do NOT create new tests by default.

You must not add new test files or test methods unless the test meets ALL of the following criteria:
1. It reproduces a specific, documented bug (regression test).
2. No existing test covers the affected integration scenario.
3. It tests a complex integration path between different modules (e.g., `forge-core` and `forge-game`), not a simple unit.

## Before adding any test, you MUST:
1. Search the codebase for existing tests covering the same logic. If found, do not duplicate.
2. State explicitly which of the three criteria above are met and why the test is necessary.

## If unsure, STOP and ask the user before creating any test.