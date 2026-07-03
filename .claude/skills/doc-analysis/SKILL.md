---
name: doc-analysis
description: Process Japanese basic design documents (xlsx/md) — extract, translate, convert to markdown, and generate detail designs with atomic design breakdown for Angular frontend and layered architecture for Spring Boot backend.
origin: ECC
---

# Document Analysis — Japanese Basic Design Processing

Transform Japanese software basic design documents (基本設計書) into actionable, English-language detail designs organized by screen code and service.

## When to Use

- Receiving a `.xlsx` or `.md` basic design from a Japanese team
- Need to translate and structure BD into implementable detail designs
- Breaking down a monolithic BD into screen-by-screen or service-by-service DDs
- Applying atomic design methodology to frontend specifications
- Converting Excel-based specs to version-control-friendly markdown

## Core Workflow

### Step 1: Extract

For `.xlsx` files, use Python openpyxl:

```python
import openpyxl, json, sys

wb = openpyxl.load_workbook(sys.argv[1], data_only=True)
result = {}
for sheet_name in wb.sheetnames:
    ws = wb[sheet_name]
    sheet_data = []
    for row in ws.iter_rows(values_only=True):
        sheet_data.append([str(cell) if cell is not None else '' for cell in row])
    result[sheet_name] = sheet_data

print(json.dumps(result, ensure_ascii=False, indent=2))
```

For merged cells that break parsing, try:
```python
# Unmerge before reading
for sheet_name in wb.sheetnames:
    ws = wb[sheet_name]
    for merged_range in list(ws.merged_cells.ranges):
        ws.unmerge_cells(str(merged_range))
```

### Step 2: Identify Structure

Japanese BD documents typically contain these sheets. Map them:

| Japanese Sheet Name | English | Content |
|---------------------|---------|---------|
| 画面一覧 | Screen List | Screen codes, names, routes |
| 画面遷移図 | Screen Flow | Navigation/mermaid diagram |
| API一覧 | API List | Endpoints, methods, params, responses |
| テーブル定義 | Table Definitions | DB schema, columns, types |
| 項目定義 | Item Definitions | Field-level specs, validation |
| 業務ルール | Business Rules | Validation logic, constraints |
| 権限一覧 | Permissions | Role-based access |
| 共通機能 | Common Functions | Shared utilities, components |

### Step 3: Translate

Key rules for Japanese→English translation:

- **Preserve codes**: Screen codes (SCR-001), item codes, API paths — keep as-is
- **Technical terms**: API, SQL, data types stay in English/original
- **Ambiguous terms**: Add original Japanese in parentheses — "承認 (shounin/approval)"
- **Date formats**: 令和X年Y月Z日 → YYYY-MM-DD
- **Numbers**: Keep as-is (Japanese uses same numerals)
- **Names**: Japanese proper names → romanized (e.g., 田中太郎 → Tanaka Taro)

### Step 4: Generate Detail Designs

Organize output by screen code or service:

```
docs/
├── BD-{name}-translated.md           # Full translated BD
├── SCR-001/                           # Screen-level DD
│   └── DD-SCR-001.md                  # Frontend detail design
├── SCR-002/
│   └── DD-SCR-002.md
├── auth-service/
│   └── DD-login-flow.md               # Backend detail design
└── stream-service/
    └── DD-stream-lifecycle.md
```

### Frontend DD Structure (Angular)

Use atomic design methodology. For each screen:

1. **Page** — Top-level route component. Orchestrates organisms.
2. **Organisms** — Composite sections (e.g., StreamDashboard, ChatPanel). Composed of molecules.
3. **Molecules** — Functional groups (e.g., StreamCard, MessageBubble). Composed of atoms.
4. **Atoms** — Single UI elements (e.g., PrimeNG Button, custom Badge).

Each DD includes:
- Component tree with props/events
- API integration points
- Form specifications with validation rules
- State management (Angular signals or services)
- Error handling per scenario
- Accessibility requirements

### Backend DD Structure (Spring Boot)

Follow the project's layered architecture. For each feature:

1. **Controller** — Endpoint mapping, request validation
2. **Service** — Business logic, transaction boundary
3. **Repository** — R2DBC query methods
4. **Domain** — Entities, DTOs, value objects

Each DD includes:
- API contract (OpenAPI-style)
- Data flow sequence
- Error mapping table
- Validation rules with BD traceability

## Working with the Planner

After generating DDs:

1. Present scope: "Found X screens, Y APIs, Z tables → N DD files"
2. For each DD, ask: "Delegate to planner for implementation task breakdown?"
3. The planner creates implementable task lists from each DD
4. DDs remain as reference artifacts, planner output drives `/implement`

## Angular Atomic Design Patterns

Apply these patterns from `rules/angular/patterns.md`:

### When to split
- A page with 3+ distinct sections → each section is an organism
- A form with 5+ fields → molecule with atom inputs
- Reused across 2+ screens → promote to shared organism
- Complex state logic → extract into a service

### When NOT to split
- Simple page with one table → single component, no atomic breakdown
- Two fields and a button → one molecule, don't split further
- Single-use helper → keep inline until reused

### Naming convention
```
Pages:     {Feature}PageComponent        (e.g., StreamDashboardPageComponent)
Organisms: {Feature}{Section}Component   (e.g., StreamListComponent)
Molecules: {Feature}{Action}Component    (e.g., StreamCreateFormComponent)
Atoms:     {Element}Component            (e.g., StreamStatusBadgeComponent)
```

## Handling Edge Cases

| Situation | Action |
|-----------|--------|
| XLSX has merged cells | Unmerge before parsing, note structure may shift |
| Sheet is a diagram (not tabular) | Describe what it shows, can't auto-extract |
| Japanese term has no good English equivalent | Keep Japanese + literal translation + contextual meaning |
| BD references external systems | Flag as integration point, note in DD |
| Contradictory specs between sheets | Highlight conflict, ask user to resolve |
| Missing security/auth spec | Flag in DD with "NEEDS CLARIFICATION" marker |
| Screen has no code assigned | Generate a provisional code, note it needs confirmation |
