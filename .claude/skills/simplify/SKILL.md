---
name: simplify
description: Review the changed code for reuse, simplification, efficiency, altitude cleanups, and classic Clean Code smells (long parameter lists, deep nesting, oversized functions), then apply the fixes. Quality only — it does not hunt for bugs; use /code-review for that.
---

# Simplify

Review the current diff (or the file(s) named) for quality issues and apply the fixes directly — this is a quality pass, not a bug hunt. Look for:

- **Reuse** — logic duplicated across files/methods that should share one implementation.
- **Simplification** — code doing something the hard way when a simpler idiom exists.
- **Efficiency** — obviously wasteful work (repeated computation, needless allocation) in a hot or frequently-called path.
- **Altitude** — a function mixing levels of abstraction (business logic next to low-level string/loop mechanics), or a comment explaining *what* the code does rather than *why* — see CLAUDE.md's comment policy.
- **Clean Code smells** — see the checklist below.

Fix what you find directly (Edit), don't just report it, unless the fix is large enough to need its own confirmation first. Stay in scope: this skill improves the code that's already there, it doesn't redesign it.

## Clean Code checklist (Robert C. Martin)

Concrete, checkable smells from *Clean Code*, distinct from architecture correctness (that's `hex-architecture-checker`'s job — don't duplicate it here; this skill is about craftsmanship within a file or function, not layer boundaries).

1. **Parameter count.** Functions/constructors should be niladic or monadic where possible; three arguments is the practical ceiling, more needs real justification. When you see a group of parameters that travel together (or a constructor call with a long run of positional args, especially several `null`s or same-typed values in a row — the kind of thing that lets `endpointIp`/`endpointPort` swap silently with no compiler error), that's a sign they belong in a class of their own.
   - **In this project specifically:** the fix for a long or positional constructor is Lombok's `@Builder` (already sanctioned — see CLAUDE.md and `.claude/agents/vaier-dev.md`), not a redesign. `@Builder` works on records. `Machine` (`src/main/java/net/vaier/domain/Machine.java`) is the worked example — a 15-component record with three factory methods calling `new Machine(...)` positionally.
2. **Block patterns.** The blocks inside `if`/`else`/`while`/`for` should usually be one line — ideally a call to a well-named function. Deep nesting (more than ~2 levels of indentation inside a method) is a sign the method is doing too much; extract the nested block into its own method with a name that says what it does, or invert the condition to return/continue early instead of nesting.
3. **Function length & one thing.** A function should do one thing, do it well, and do it only. If you can extract a coherent chunk of a function and give it a name that isn't just a restatement of the code, it should be its own function. One level of abstraction per function — don't mix "orchestrate the steps" with "compute a checksum" in the same method body.
4. **Flag arguments.** A boolean parameter that makes a function behave in two genuinely different ways means the function is doing two things — split it into two functions instead of branching inside one.
5. **Naming.** Names should say what they do without needing a comment to explain them; no abbreviations that aren't already the project's own vocabulary (see `UBIQUITOUS_LANGUAGE.md`), no magic numbers/strings without a named constant.

## Scope boundary

- **This skill**: code-level craftsmanship inside a file/function/class — parameter lists, nesting, function size, naming, duplication.
- **`hex-architecture-checker`**: layering and port/adapter correctness — where a decision lives, whether a service calls a use case it shouldn't, driven-port implementation.
- **`vaier-reviewer`** / **`/code-review`**: broader conventions and correctness — TDD evidence, ubiquitous language, DTO placement, doc-sync, actual bugs.

Don't re-do another tool's job; if you notice a hex-layering issue while simplifying, mention it but don't fix it here — that's `hex-architecture-checker`'s call to make with full context.
