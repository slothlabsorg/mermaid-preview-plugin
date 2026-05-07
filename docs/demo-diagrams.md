# Demo: Mermaid diagrams showcase

A grab-bag of Mermaid diagram types. Open this file with the **Mermaid Preview** plugin installed to see every supported diagram kind rendered side-by-side.

> **Heads up:** section **§11 (Quadrant chart)** contains an **intentional syntax error** (the `x-axis` arrow is malformed) to showcase the plugin's error-overlay feature. The plugin highlights the offending line and shows the mermaid parser message. Fix the arrow to see it render.

---

## 1. Flowchart — build pipeline

```mermaid
flowchart LR
    A[pull request] --> B{lint passes?}
    B -- no --> A
    B -- yes --> C[run tests]
    C --> D{green?}
    D -- no --> A
    D -- yes --> E[build artifact]
    E --> F{is tagged release?}
    F -- no --> G[store as snapshot]
    F -- yes --> H[publish to registry]
    H --> I([notify Slack])
    G --> I
```

---

## 2. Sequence diagram — OAuth 2.0 authorization code flow

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App as Client App
    participant AS as Auth Server
    participant API as Resource Server

    User->>App: click "Sign in with Provider"
    App->>AS: /authorize?client_id&redirect_uri&scope
    AS->>User: login + consent screen
    User->>AS: credentials + approve
    AS-->>App: redirect with code
    App->>AS: /token (code + client_secret)
    AS-->>App: access_token + refresh_token

    Note over App,API: token lives ~1 hour

    App->>API: GET /me (Bearer access_token)
    API-->>App: 200 profile
    App->>User: signed in
```

---

## 3. State diagram — order lifecycle

```mermaid
stateDiagram-v2
    [*] --> Draft
    Draft --> Submitted : place order
    Submitted --> Paid : payment ok
    Submitted --> Cancelled : timeout / user cancel
    Paid --> Shipped : warehouse picks
    Shipped --> Delivered : courier confirms
    Delivered --> [*]
    Paid --> Refunded : return approved
    Refunded --> [*]
    Cancelled --> [*]

    note right of Paid
        order value is captured
        inventory is reserved
    end note
```

---

## 4. Class diagram — a tiny domain model

```mermaid
classDiagram
    class User {
        +Long id
        +String email
        +String displayName
        +List~Order~ orders
        +signIn(password) Session
    }

    class Order {
        +UUID id
        +User customer
        +List~Item~ items
        +OrderStatus status
        +total() Money
    }

    class Item {
        +String sku
        +int quantity
        +Money unitPrice
    }

    class OrderStatus {
        <<enumeration>>
        DRAFT
        SUBMITTED
        PAID
        SHIPPED
        DELIVERED
        CANCELLED
        REFUNDED
    }

    User "1" --> "*" Order
    Order "1" --> "*" Item
    Order --> OrderStatus
```

---

## 5. Entity-relationship diagram — library schema

```mermaid
erDiagram
    AUTHOR ||--o{ BOOK : writes
    BOOK ||--|{ COPY : "has copies"
    MEMBER ||--o{ LOAN : borrows
    COPY ||--o{ LOAN : "is loaned as"

    AUTHOR {
        int id PK
        string name
        date birthdate
    }
    BOOK {
        string isbn PK
        string title
        int author_id FK
        int year_published
    }
    COPY {
        int id PK
        string isbn FK
        string shelf_location
        string condition
    }
    MEMBER {
        int id PK
        string name
        string email
        date joined_on
    }
    LOAN {
        int id PK
        int copy_id FK
        int member_id FK
        date borrowed_on
        date returned_on
    }
```

---

## 6. Gantt — sprint planning

```mermaid
gantt
    title Release 2026.Q3 — 4-week sprint
    dateFormat  YYYY-MM-DD
    axisFormat  %b %d

    section Backend
    API redesign           :a1, 2026-06-01, 7d
    Auth refactor          :after a1, 5d
    DB migration           :crit, 2026-06-10, 3d

    section Frontend
    Design review          :b1, 2026-06-01, 4d
    Component library      :after b1, 10d
    Integration            :2026-06-15, 7d

    section QA
    Test plan              :c1, 2026-06-08, 5d
    E2E automation         :after c1, 8d
    Regression             :milestone, 2026-06-26, 0d

    section Release
    Staging deploy         :d1, 2026-06-25, 2d
    Smoke tests            :after d1, 1d
    Prod deploy            :milestone, 2026-06-28, 0d
```

---

## 7. Pie chart — time spent last sprint

```mermaid
pie showData title Sprint 42 — engineer time by category
    "Feature work" : 52
    "Bug fixes" : 18
    "Code review" : 12
    "Meetings" : 9
    "On-call / incident" : 6
    "Documentation" : 3
```

---

## 8. Git graph — feature branch workflow

```mermaid
gitGraph
    commit id: "init"
    commit id: "seed data"
    branch feat/search
    checkout feat/search
    commit id: "indexer"
    commit id: "ranking"
    checkout main
    branch fix/cache
    commit id: "invalidation"
    checkout main
    merge fix/cache tag: "v1.2.1"
    checkout feat/search
    commit id: "filters"
    checkout main
    merge feat/search tag: "v1.3.0"
    commit id: "post-release"
```

---

## 9. User journey — onboarding funnel

```mermaid
journey
    title New user onboarding — first 48 hours
    section Signup
      Visit landing page: 5: Visitor
      Click "Sign up":    4: Visitor
      Confirm email:      3: Visitor
    section First use
      Complete profile:   4: User
      Create first project: 5: User
      Invite a teammate:  3: User
    section Activation
      Receive first notification: 5: User
      Return next day:    4: User
      Reach "aha moment": 5: User
```

---

## 10. Mindmap — plugin architecture (meta)

```mermaid
mindmap
  root((Mermaid Preview))
    Tool Window
      JCEF browser
      Card per block
      Toggle Diagram/Code
    File watching
      FileEditorManager
      DocumentListener
      Debounce 250ms
    Rendering
      Bundled mermaid 10.9.3
      Dark/light theme
      Parse error overlay
    Distribution
      GitHub Releases
      Build action
      Future::Marketplace
```

---

## 11. Quadrant chart — prioritizing features

```mermaid
quadrantChart
    title Feature prioritization
    x-axis Low Effort --> High Effort
    y-axis Low Impact --> High Impact
    quadrant-1 Do first
    quadrant-2 Schedule
    quadrant-3 Skip
    quadrant-4 Delegate
    Toggle Code/Diagram: [0.2, 0.9]
    Export PNG: [0.4, 0.7]
    Inline editor inlays: [0.85, 0.85]
    Theme hot-swap: [0.6, 0.4]
    Fullscreen mode: [0.3, 0.5]
    Marketplace publish: [0.55, 0.75]
    D2/PlantUML support: [0.9, 0.3]
```

---

## 12. Timeline — mermaid release history (abridged)

```mermaid
timeline
    title Mermaid milestones
    2014 : Mermaid project started by Knut Sveidqvist
    2018 : Flowchart + Sequence + Gantt stable
    2019 : Class diagrams added
    2021 : ER diagrams · Pie · journey
    2022 : gitGraph stabilized · Mindmap beta
    2023 : v10 — ESM, async API, mermaid.parse
    2024 : Quadrant · Timeline · Sankey
    2026 : You're reading this in a plugin that renders it live
```
