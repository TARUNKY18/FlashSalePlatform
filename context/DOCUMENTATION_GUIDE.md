# DOCUMENTATION_GUIDE.md
## Flash Sale Platform — Context File Responsibilities
**Authority:** This document defines what each context file owns, how often it changes,
and what is explicitly forbidden from appearing inside it.
**Rule:** When in doubt about where something belongs, this document decides.
**Version:** 1 — established post-Week-1 audit (2026-06-17)

---

## Why this document exists

Six context files travel with this project. Without a clear ownership boundary,
the same fact ends up in two files, they drift apart, and a future session inherits
contradictions. Every file below has exactly one job. Nothing more.

---

## Files in scope

1. `PROJECT_TRUTH.md`
2. `CURRENT_STATE.md`
3. `SESSION_LOG.md`
4. `CONFLICTS.md`
5. `REPOSITORY_INDEX.md`
6. `TASK_TEMPLATE.md`

---

## 1. PROJECT_TRUTH.md

### Purpose
The single canonical architecture reference for the entire platform.
Any agent, engineer, or session that needs to understand *what this system is
designed to be* reads this file first. It is the one document that, if lost,
cannot be reconstructed from any other single source.

### Update frequency
- On milestone completion (e.g., Week 1 → Week 2 boundary)
- When a component transitions from PLANNED → VERIFIED or NOT VERIFIED → VERIFIED
- When an ADR is approved or its implementation status changes
- When the domain model, API contracts, or schema design changes

### Allowed contents
- Project overview, purpose, and product goal
- Source document precedence order (per AI-CONTEXT.md)
- Business rules and non-functional requirements
- Acceptance criteria
- Full ADR inventory with design status and implementation status
- Technology stack with VERIFIED / PLANNED / NOT VERIFIED per component
- Service definitions (what each service owns, its DB, Kafka role, Redis role, port)
- Domain model: aggregate roots, entities, value objects, bounded contexts
- Domain event catalogue
- Infrastructure: repository structure, Postgres instances, Redis topology, Kafka topics,
  ClickHouse schema, component status rollup
- API contracts (endpoint list with owner and notes)
- Redis key/layer design
- Kafka consumer groups and producer/consumer configuration
- Database schema design (tables, invariants, key query patterns)
- Runtime verification rollup (one row per component — status only, no prose)
- Current build plan and immediate next steps

### Forbidden contents
- Open issues, bugs, or technical debt items — **these belong in CURRENT_STATE.md only**
- Session-specific observations or commands run
- Inline resolution of documentation conflicts — **these belong in CONFLICTS.md**
- Duplicate of any field already owned by CURRENT_STATE.md (e.g., detailed
  health check output, branch name, git log)
- Any content that changes more than once per milestone (if it changes every session,
  it belongs in CURRENT_STATE.md)

---

## 2. CURRENT_STATE.md

### Purpose
The live operational snapshot of the current milestone. It answers one question:
*"What is the state of the system right now, in this session, on this machine?"*
It is the only file that changes every session.

### Update frequency
- Every session where infrastructure state changes
- Every session where health checks are run
- Whenever an open issue is discovered, resolved, or promoted to an ADR
- At every milestone transition (old milestone marked COMPLETE, new milestone defined)

### Allowed contents
- Milestone name and completion status
- Completed work for the current milestone (bullet list, verified facts only)
- Bugs found and fixed table (ID, description of fix)
- Running services table (container name, image, port, health status)
- Validation evidence (verbatim terminal output from `make health`)
- Open issues table (ID, description, priority, target week)
- Current git branch
- Next milestone summary (goal and done-when condition)

### Forbidden contents
- Architecture decisions — **these belong in PROJECT_TRUTH.md**
- ADR content, domain model, schema design
- Documentation conflicts — **these belong in CONFLICTS.md**
- Directory structure — **this belongs in REPOSITORY_INDEX.md**
- Duplication of PROJECT_TRUTH.md's component status rollup
- Historical milestone records (only the current milestone lives here;
  completed milestones are archived in SESSION_LOG.md)

---

## 3. SESSION_LOG.md

### Purpose
The append-only record of every working session. It answers:
*"What happened in each session, in what order, and what was decided?"*
It is never read by an agent at the start of a session — it is an audit trail
for humans, not a context source.

### Update frequency
- Appended at the end of every working session
- Never edited retroactively (append-only by rule)

### Allowed contents
- Session date and milestone week
- Commands run and their verbatim outputs (summarised, not exhaustive)
- Bugs discovered during the session (before they are moved to CURRENT_STATE.md)
- Decisions made during the session (before they are promoted to PROJECT_TRUTH.md or an ADR)
- Files generated or modified during the session
- Self-test Q&A results (questions attempted, grades, corrections)
- What was left incomplete and why

### Forbidden contents
- Architecture decisions as a final home — decisions made here must be
  promoted to PROJECT_TRUTH.md or an ADR before the session ends
- Open issues as a final home — open issues discovered here must be moved
  to CURRENT_STATE.md before the session ends
- Forward-looking plans (those belong in CURRENT_STATE.md's Next Milestone section)
- Any content that is edited after it is written (this file is append-only)

---

## 4. CONFLICTS.md

### Purpose
The authoritative log of every unresolved documentation inconsistency across
the repository. It is the only document that may contain contradictions — because
its job is to name them, not resolve them. An agent that encounters a conflict
during a task must stop, record it here, and ask before proceeding.

### Update frequency
- When a new conflict is discovered (append a new CONFLICT-NNN entry)
- When Tarun resolves a conflict (update the Decision and Resolution fields;
  do not delete the entry — mark it RESOLVED)

### Allowed contents
- Conflict ID (CONFLICT-001 through CONFLICT-NNN)
- Status: OPEN or RESOLVED
- Category: Documentation Error / Design Decision / Future Implementation
- Documents involved (with exact quoted excerpts from each)
- Reason the discrepancy matters (why it affects implementation)
- Precedence recommendation (what AI-CONTEXT.md's source order would suggest —
  labelled as a recommendation only, never as a resolution)
- Decision field (Pending until Tarun closes it)
- Owner field (always Tarun)
- Resolution field (filled only when Decision is no longer Pending)

### Forbidden contents
- Resolved conflicts removed or deleted — **mark RESOLVED, never delete**
- Implementation guidance or architecture decisions
- New ADR content (decisions that have been made belong in 01-Decisions.md,
  not in CONFLICTS.md)
- Speculative conflicts (only document inconsistencies between actual document
  text — never hypothetical future contradictions)

---

## 5. REPOSITORY_INDEX.md

### Purpose
The directory structure reference. It answers: *"Does this file or directory
exist, and if so, what is it for?"* Every directory and file in the repository
has exactly one entry here, with a status of EXISTING, PLANNED, or NOT VERIFIED,
and evidence for any EXISTING claim.

### Update frequency
- When a new file or directory is created and confirmed
- When a PLANNED entry transitions to EXISTING (requires evidence — a terminal
  command output, screenshot, or explicit confirmation)
- At milestone transitions when new directories are expected to be created

### Allowed contents
- Directory entries with status (EXISTING / PLANNED / NOT VERIFIED)
- Evidence for EXISTING status (which command confirmed it, what output appeared)
- Purpose of each directory (one sentence)
- Owner (which service or team is responsible)
- Dependencies (what must exist before this directory can be created)
- Target milestone for PLANNED entries
- Summary table at the bottom (count of existing vs planned)

### Forbidden contents
- File contents (REPOSITORY_INDEX.md lists files, it does not describe what is
  inside them — that belongs in PROJECT_TRUTH.md)
- Architecture decisions
- Runtime health status — **this belongs in CURRENT_STATE.md and PROJECT_TRUTH.md**
- Documentation conflicts

---

## 6. TASK_TEMPLATE.md

### Purpose
The workflow protocol for every implementation task. It is not a document about
the project — it is a document about *how to work on* the project. Its only job
is to ensure that every task starts with the correct context loaded and ends
with a reviewable plan before any code is generated.

### Update frequency
- Rarely — only when the protocol itself changes
- Examples of valid changes: a new context file is added that all tasks must read;
  the output format is amended; a new mandatory output field is added
- This file should survive many weeks without changing

### Allowed contents
- The list of files to read before starting any task
- The `<task>` placeholder (filled per use, never stored here)
- The required output structure (files to create, files to modify, dependencies,
  risks, assumptions)
- The `Do not generate code yet.` gate

### Forbidden contents
- Task-specific content — the `<task>` field is a placeholder, not a stored value;
  previous task descriptions must never be committed into this file
- Architecture content of any kind
- Session history or outcomes
- Any content that varies between tasks (if it varies, it belongs in the task
  description, not in the template)

---

## Ownership matrix

| Content type | Belongs in |
|---|---|
| Architecture decisions and ADRs | `PROJECT_TRUTH.md` |
| Component VERIFIED / PLANNED / NOT VERIFIED status | `PROJECT_TRUTH.md` |
| Domain model, schema design, API contracts | `PROJECT_TRUTH.md` |
| Running containers and health check output | `CURRENT_STATE.md` |
| Open issues and known bugs | `CURRENT_STATE.md` only |
| Current milestone status | `CURRENT_STATE.md` |
| What happened in a session | `SESSION_LOG.md` |
| Decisions made during a session (before promotion) | `SESSION_LOG.md` |
| Documentation inconsistencies | `CONFLICTS.md` |
| Directory and file existence | `REPOSITORY_INDEX.md` |
| Task workflow protocol | `TASK_TEMPLATE.md` |

## Conflict escalation rule

If two files contain the same fact and they disagree, the following order applies:

1. **Check CONFLICTS.md first.** If the disagreement is already logged, do not act
   — wait for Tarun's decision.
2. **If not logged, log it in CONFLICTS.md** using the CONFLICT-NNN format.
3. **Apply AI-CONTEXT.md source precedence** as the recommendation only.
4. **Never resolve silently.** Every unresolved contradiction surfaces in CONFLICTS.md
   before any implementation proceeds.

## Promotion rule

Facts discovered during a session move upward through this hierarchy:

```
SESSION_LOG.md          ← discovered here first
    ↓ if it's a bug or defect
CURRENT_STATE.md        ← open issues tracked here
    ↓ if it becomes an architectural decision
PROJECT_TRUTH.md        ← architecture reflected here
    ↓ if it's a formal decision with a rationale
docs/adr/01-Decisions.md ← decisions ratified here
```

A fact that has been promoted must be removed from the lower file to prevent
duplication. The exception is SESSION_LOG.md — it is append-only and its entries
are never deleted, even after promotion.

---

*Owner: Tarun K Y*
*Last updated: 2026-06-17 (post-Week-1 audit)*
*Next review: Week 2 milestone completion*