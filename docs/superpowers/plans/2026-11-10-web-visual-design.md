# Novvin Web Visual Implementation Plan

**Goal:** Deliver a coherent colorful first visual increment while preserving navigation and content.
**Architecture:** Reuse existing tokens and public static rendering. Extract only the primary app navigation for focused testing; isolate visual styles to the relevant surfaces.
**Tech Stack:** Existing React, TypeScript, CSS, Vite, Jest.
**Spec:** docs/superpowers/specs/2026-11-10-web-visual-design.md

## Global Constraints
Only correcciones-dot. No deployment or backend edits. Preserve account/session/draft behavior, all five destinations and theme preferences. No new dependencies or fabricated public claims.

## Review Focus
Narrow mobile reflow; light/system/custom accent behavior; long navigation labels and counts; routes with their own media viewport; public HTML without JavaScript.

### Task 1: Public visual hierarchy
Files: PublicHome.tsx, public-home.css, PublicHomeDesign.test.tsx under NoWing-web/src.
- [x] Write and run failing tests for labeled product illustration, all account/guest/Android links and a purposeful discovery group.
- [x] Implement the product-led hero and scoped visual system, preserving existing information and routes.
- [x] Run component tests; verify static public build/SEO.
- [ ] Verify narrow reflow in a rendered Preview (local browser blocked).

### Task 2: Application navigation and Social
Files: ui/novvin/AppNavigation.tsx; layout/AppLayout.tsx; ui/novvin/shell.css; features/social/SocialPage.tsx; ui/novvin/webParity.css; pages/explore.css; focused component tests.
Interfaces: AppNavigation receives activeSection, badges, avatar URL and display name, and calls the existing openNovvinMenu event for the full menu.
- [x] Write and run failing tests for all primary destinations, current page, badge information and menu action; add Social tab tests including composer close and Rondas.
- [x] Extract navigation without changing auth and other layout behavior. Add responsive wide-desktop treatment, scoped Social introduction and guest surfaces.
- [x] Run focused tests and the full Jest suite; typecheck/build.
- [ ] Inspect synthetic visual fixtures, keyboard and reflow in Preview.

### Task 3: Integration evidence
- [x] Record exact verification results and visual limitations in docs/audits.
- [x] Run diff checks and complete independent code review.
- [x] Prepare the reviewed changes for integration.

## Decisions during execution

- The owner delegated direct execution, so the design was chosen without an extra approval round. A focused increment was preferred over replacing the whole UI stack; a broader redesign remains possible after visual acceptance.
- Browser execution was blocked by local socket restrictions. The visual gate remains open; no screenshot or accessibility conformance was inferred from source tests.
- The CSS budget failure was corrected by consolidating existing styles, not increasing the threshold.
- Independent review found Videos/theme and Social/custom-accent contrast defects. Source-contract regressions failed before the fixes and passed afterward.
