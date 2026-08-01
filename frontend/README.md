# FlowAI frontend

React 19 + TypeScript + Vite. Server state via TanStack Query, routing via React Router,
drag and drop via dnd-kit.

```bash
npm install
npm run dev          # Vite dev server on 5173, proxies /api to localhost:8080
npm run lint
npm test             # Vitest unit and component tests
npm run test:e2e     # Playwright, spins up its own backend and database
npm run build
```

## Styling architecture

Three layers, each with a job. They are not interchangeable, and the boundary
between them is the rule below.

### 1. Design tokens — `src/index.css`

One set of CSS custom properties, consumed by everything else. Tailwind's
`@theme inline` block turns them into utilities (`bg-background`, `border-border`),
and `@layer base` carries the reset.

Tailwind is used here as a **token and reset engine**, not as a utility framework.
Outside of `src/components/ui/`, business components do not write Tailwind
utility classes.

The design language is **Material Design 3 (Material You)**, generated from a
`#6750A4` seed. The token file has two tiers, and the split is why a restyle of
this size is a token edit rather than a rewrite:

1. **`--md-*` roles are the source of truth** — the MD3 tonal palette, shape
   scale, elevation, state-layer opacities and motion curve, transcribed to
   oklch so they mix in the same space as everything else.
2. **The shadcn/legacy names are aliases onto them.** `--background`,
   `--muted`, `--border` and friends are pointers, not values. Feature CSS and
   the shadcn primitives keep their existing names and inherit MD3 for free.

**Write new work against `--md-*`.** The aliases exist to carry the existing
surface area, not as a second vocabulary to design in.

Rules worth knowing:

- **Never a pure-white surface.** Depth comes from the tonal ramp
  (`--md-surface` → `--md-surface-container` → `--md-surface-container-high`),
  not from shadows. `--card` deliberately resolves to a *tinted* container.
- **Interaction uses state layers, not colour swaps.** Filled surfaces dial
  their own colour down (`/90` hover, `/80` pressed); transparent ones pick the
  primary up (`/10` hover). Hover should read as *the same control, touched*.
- **Use `in oklab`, not `in oklch`, when mixing a chromatic colour toward a
  neutral.** oklch interpolates around the hue circle, so the seed violet
  (H≈294) drifts toward pink on its way to white. oklab is rectangular and keeps
  the hue. Mixing with `transparent` is safe in either space.
- **`--status-*` is a validated categorical palette, not a free colour choice.**
  Those five tokens identify workflow state on the board, in the list, *and* as
  chart series in analytics, so they were picked by running the palette through
  a colour-blindness/contrast validator rather than by eye. In-progress is
  anchored to the seed; Todo is a cyan specifically because the obvious choice
  (the MD3 secondary) failed both the chroma floor and the normal-vision
  separation floor next to the primary purple. **Re-validate before changing
  one.** Analytics consumes these via `--analytics-*` aliases — it does not
  define its own series colours.
- **Both light and dark palettes are authored**, but nothing toggles `.dark`
  yet, so `color-scheme` is declared `light` to keep native controls
  (scrollbars, date pickers) consistent with the page. Dark is not a lightened
  copy: the accessible band on a dark surface is L 0.48–0.67, so the status
  colours have their own validated steps there.
- `--brand` is retained as an alias of `--md-primary` so older selectors keep
  resolving; prefer `--md-primary` in new code.

### 2. Component library — `src/components/ui/`

shadcn components, generated into the repo and owned by us. These exist for
primitives where **focus management, keyboard navigation, or ARIA wiring is hard
to get right by hand** — the parts Radix gives us for free.

### 3. Feature CSS — colocated `*.css` files

Plain CSS with semantic class names (`.sidebar-list-item`, `.kanban-card-open`),
imported through `src/App.css`. This owns layout and visual composition. It reads
tokens and does not hardcode colours.

## The boundary rule

> If it needs focus management or keyboard behaviour, it belongs to the component
> library. If it only needs to sit in the right place and read the right tokens,
> it belongs to feature CSS.

Concretely:

| Element | Renders as | Why |
| --- | --- | --- |
| Actions, nav rows, toolbar controls | `<Button>` | One shared focus ring and disabled treatment |
| Menus, dialogs, selects, popovers | shadcn primitive | Focus trap, roving focus, `aria-*` wiring |
| Whole-card / whole-row click targets | native `<button>` | `<Button>`'s `inline-flex` and `justify-center` would fight the multi-line internal layout |
| Drag handles | native `<button>` | Carries dnd-kit's `{...listeners}`; must stay unwrapped |
| Overlay backdrops | native `<button>` | A full-bleed transparent hit area; every `<Button>` base style would have to be undone |

### Combining `<Button>` with a semantic class

`<Button className="sidebar-list-item">` is a supported combination, not a
workaround. Tailwind's utilities live in `@layer utilities`; feature CSS is
unlayered, and **unlayered rules beat layered ones** regardless of specificity. So
the semantic class wins every property it declares, and `<Button>` supplies only
what is left over — the focus ring, disabled handling, and `data-slot` hooks.

The catch: it wins only what it *declares*. Base utilities the semantic class is
silent about still land. When adopting `<Button>` on an existing class, check for
`h-9` (the default size fixes the height), `rounded-full` (every variant is a
pill) and `whitespace-nowrap` (stops text wrapping), and reclaim them
explicitly — see `.sidebar-list-item` in `src/styles/auth-and-shell.css`.

### Button variants

Pill shape is not decoration — it is the most recognisable trait of MD3, so
there is no `rounded` escape hatch. The `fab` variant is the one exception the
spec allows.

| Variant | MD3 role | Use for |
| --- | --- | --- |
| `default` | Filled | The primary action on a surface |
| `tonal` / `secondary` | Secondary container | Secondary actions that still need weight |
| `outline` | Outlined | Toolbar controls, filters |
| `ghost` | Text | Icon actions in the sidebar and toolbars |
| `destructive` | Error | Irreversible actions (error container, not a filled red) |
| `fab` | FAB | Floating actions — squircle, tertiary, real elevation |

Two more traps worth knowing before you reclaim positioning from a component:

- **Tailwind v4 centres with `translate`, not `transform`.** `-translate-x-1/2`
  compiles to the standalone `translate` property, so `transform: none` leaves
  the offset in place. Reset `translate: none` as well — see `.ai-drawer` in
  `src/features/ai-copilot/ai-copilot.css`.
- **Measure after the open animation.** `DialogContent` ships
  `data-open:zoom-in-95`, so anything that reads `getBoundingClientRect()` right
  after the panel appears sees a scaled, offset box rather than the resting one.

### Dialogs

Every modal renders through `ModalShell` (`src/features/project-shell/feature-ui.tsx`),
which wraps shadcn's `Dialog`. Callers mount it conditionally instead of passing
an `open` flag, so the Dialog is hard-coded open and closing routes back through
`onClose`. One consequence: Radix never sees a closed→open transition and has no
trigger to restore focus to, so `ModalShell` captures the previously focused
element during its first render and hands focus back in `onCloseAutoFocus`.

Radix does not set `aria-modal`; it marks `#root` with `aria-hidden="true"`
instead, which removes the background from the accessibility tree outright.

When using `<DialogTitle asChild>`, do **not** put your own `id` on the child.
Radix generates an id and points `aria-labelledby` at it; a hand-written id
silently wins, and the dialog ends up with no accessible name — which breaks
`getByRole('dialog', { name })` in the E2E suite.
