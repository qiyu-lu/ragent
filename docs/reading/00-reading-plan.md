# Large Java Project Reading Plan

## Purpose

This document defines how to read this project in small, repeatable rounds without exhausting chat context. It is a working protocol for future Codex sessions and manual reading.

The goal is not to read every file. The goal is to build an accurate project map, understand one core execution path, then expand module by module.

## Reading Goals

- Understand the project type, runtime model, and major technical stack.
- Identify module boundaries and the responsibilities of important packages.
- Trace at least one representative main flow end to end.
- Record stable findings in repo-local notes so future sessions can resume without rereading everything.
- Separate facts from assumptions and keep unresolved questions visible.

## Boundaries

- Default mode is read-only.
- Do not modify production code unless the user explicitly asks for implementation changes.
- Do not scan the whole repository without a narrow question.
- Do not summarize files that were not actually inspected.
- Do not treat inferred behavior as fact. Mark it as an assumption or open question.
- Each reading round should have one concrete goal and a limited file scope.

## Context Budget Rules

- Prefer reading high-signal files first: README, build files, configuration files, entrypoints, package structure, and files directly on the selected call path.
- Avoid opening many similar classes in one round.
- For broad discovery, use file names and directory structure before reading file contents.
- For code flow analysis, follow references from entrypoint to downstream collaborators instead of jumping across unrelated modules.
- Keep each round's written output short enough to be useful in later sessions.

## Reading Artifacts

Use these files as the durable reading record:

- `docs/reading/00-reading-plan.md`: reading protocol and task templates.
- `docs/reading/01-project-map.md`: project type, modules, stack, entrypoints, and next reading targets.
- `docs/reading/02-startup-and-config.md`: startup flow, configuration loading, environment dependencies, and local run notes.
- `docs/reading/03-main-flow.md`: one representative end-to-end business or execution flow.
- `docs/reading/04-module-notes.md`: focused notes for individual modules.
- `docs/reading/99-open-questions.md`: unresolved questions, assumptions, and verification tasks.

## Phase 1: Project Map

Goal: understand what this project is before entering business logic.

Allowed scope:

- Top-level directory names.
- README and documentation index files.
- Build files such as `pom.xml`, `build.gradle`, or `settings.gradle`.
- Runtime configuration files such as `application.yml`, `application.properties`, `.env.example`, and Docker files.
- Application entrypoint class names and package names, only as needed.

Questions to answer:

- What kind of project is this?
- What are the main modules or packages?
- What frameworks, infrastructure, and external services appear to be used?
- Where does the application start?
- Which files should be read next, and why?

Output file:

- `docs/reading/01-project-map.md`

## Phase 2: Startup And Configuration

Goal: understand how the application boots and what it needs at runtime.

Allowed scope:

- Main application class.
- Configuration classes.
- Bean definitions.
- `ApplicationRunner`, `CommandLineRunner`, schedulers, listeners, or similar startup hooks.
- Runtime configuration files.
- Docker or local development setup files.

Questions to answer:

- How is the application started?
- Which configuration files are loaded?
- Which important beans, clients, or services are initialized?
- What external dependencies are required locally?
- What environment variables, ports, databases, queues, caches, or model services are needed?

Output file:

- `docs/reading/02-startup-and-config.md`

## Phase 3: Main Flow

Goal: trace one representative flow from input to output.

Possible flow shapes:

- HTTP request -> Controller -> Service -> Repository or Client -> response.
- User input -> task dispatcher -> handler -> tool/model/data access -> result.
- Scheduled trigger -> job runner -> domain service -> side effect.
- Message/event -> listener -> processor -> persistence/external call.

Questions to answer:

- What triggers the flow?
- What is the first project-owned class that handles it?
- Which classes form the direct call chain?
- Where is state read or written?
- Where are external systems called?
- What is returned or produced?
- What validations, errors, retries, or transactions matter?

Output file:

- `docs/reading/03-main-flow.md`

## Phase 4: Module Reading

Goal: read independent modules after the main flow is clear.

Candidate modules:

- API layer.
- Authentication and authorization.
- Configuration and dependency wiring.
- Domain services.
- Persistence and repositories.
- Cache usage.
- Message queue or event handling.
- Scheduled jobs.
- External clients.
- Model or agent orchestration.
- Error handling.
- Testing strategy.

Module questions:

- What responsibility does this module own?
- What are the core classes?
- How does it connect to the main flow?
- What configuration or external dependency does it rely on?
- What design choices are worth learning?
- What risks or unclear behavior should be verified later?

Output file:

- `docs/reading/04-module-notes.md`

## Phase 5: Consolidation

Goal: turn scattered notes into a useful mental model.

Tasks:

- Update the project map if earlier assumptions changed.
- Add unresolved items to `99-open-questions.md`.
- Extract one or two diagrams if they would reduce future rereading.
- Create a short interview-style explanation only after the technical facts are clear.

## Single-Round Task Template

Use this prompt for a controlled reading round:

```text
Please perform one read-only project reading round.

Goal:
- <specific goal>

Allowed scope:
- <files/directories allowed>

Do not:
- Read unrelated modules.
- Modify production code.
- Summarize files that were not inspected.

Please output:
- Files inspected and why.
- Facts found.
- Assumptions or open questions.
- Recommended next reading target.
- Update the relevant file under docs/reading/.
```

## Module Note Template

Use this format when documenting a module:

```text
## <Module Name>

### Files Inspected

- `<path>`: <why this file matters>

### Responsibility

<What this module owns.>

### Core Classes

- `<ClassName>`: <role>

### Flow Position

<Where this module participates in startup, request handling, task execution, or background processing.>

### Configuration And Dependencies

- <config key, external service, bean, table, queue, cache, model, etc.>

### Facts

- <verified statement from inspected files>

### Assumptions

- <inference that still needs verification>

### Open Questions

- <question to resolve in a later round>
```

## Multi-Session Coordination

Use multiple Codex sessions only after the project map and at least one main flow exist.

Recommended roles:

- Main session: owns reading plan, project map, and final consolidation.
- Session A: reads one business or execution flow.
- Session B: reads one infrastructure module, such as persistence, cache, queue, or external clients.
- Session C: reads startup, testing, or local runtime setup.

Each secondary session must return notes using the same module note template. The main session should merge conclusions into `docs/reading/` and keep conflicting claims in `99-open-questions.md` until verified.

## When To Create A Skill

Create a custom skill only after this workflow has been used successfully for several rounds.

A future skill should enforce:

- Read-only by default.
- One goal per round.
- Limited file scope.
- Required note updates under `docs/reading/`.
- Clear separation of facts, assumptions, and open questions.
- No full-repository scan without explicit approval.

Do not create the skill before the process is proven useful on this project.
