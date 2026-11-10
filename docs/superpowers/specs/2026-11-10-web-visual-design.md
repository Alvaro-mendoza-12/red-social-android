# Novvin web: visual direction

## Intent and constraints
Make the existing website more inviting, colorful and easier to understand without removing content or inventing capabilities. The owner delegated design decisions and direct execution. This is an incremental visual revision, not a production release, on `correcciones-dot` only, based on `fc3c796dc9a50a864d8b95e555622f11cece08a5`.

Retain React, Vite, the current routes, guest access, public static rendering and all existing safety/session/draft behavior. No production data, external writes, new tracking, dependencies or backend changes. Preserve user themes and accent selections.

## Audit and approaches
Source inspected: PublicHome, PublicNavigation, public-home.css, tokens.css, foundation.css, shell.css, AppLayout navigation, SocialPage, ExplorePage and explore.css. The current public site was visually inspected on 10 November 2026. The hero uses much of the first viewport to repeat the logo. Feature illustrations are small and distant from the first action. The authenticated desktop rail is 84px wide, with small labels and secondary navigation behind an icon menu. Existing preference tokens already support accessible light/dark accent variants and should be reused.

Considered: a broad replacement with a new UI framework (too much risk and duplication), a cosmetic color-only pass (does not address hierarchy), and a focused hierarchy/color revision using existing components (selected as the builder's decision under the owner's autonomous instruction).

## Design
- Public site: navy/ink background with cyan as primary action and small violet/coral accents; a clear display headline, product-oriented illustrative panel explicitly marked as an example, preserved account/guest/Android entry points, better grouped feature cards and guide directory.
- Application: retain the five primary destinations and all routing. Make navigation a small reusable component, show the brand and readable horizontal labels on wide desktops, add clearly named existing create/explore actions; retain the compact rail at intermediate sizes and the current five-item mobile bar.
- Social: a compact page introduction and properly named navigation group, with differentiated accents and existing tab state/actions unchanged. Keep content first, avoid a tall marketing hero inside the app.
- Guest discovery: improve heading, spacing, selected states and content surfaces without changing loading, failure, empty or authorization behavior.

## Evidence and non-goals
Discord's design notes emphasize consistent spacing, legibility and the same visual language across desktop/mobile: https://discord.com/blog/improving-mobile-with-squircles-styles-and-spacing . Bluesky makes feed choice explicit: https://bsky.social/about/blog/7-27-2023-custom-feeds . The application here is clearer existing destinations, not a claim that Novvin has custom feeds. Contrast and focus requirements: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html and https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html . These are qualitative design precedents, not proof of market fit, retention or conversion.

## Acceptance
Verify existing 992 Jest tests, new navigation/public/social behavior tests, TypeScript/build and static SEO tests. Inspect desktop/mobile, narrow reflow, light/dark and keyboard focus with synthetic data. Keep before/after evidence and report any browser limitations explicitly. No claim of whole-app WCAG conformance or complete redesign across all modules.
