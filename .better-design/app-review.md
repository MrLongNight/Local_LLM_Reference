# UI review — Local LLM Reference

**Coverage:** 1 native screen found (MainActivity); 1 source/copy-reviewed; 0 critical, 0 serious, 0 moderate and 0 minor findings remain in the checks that ran. Rendered-device review is not complete.

## Scope and design direction

- The app currently has one native activity and one primary workspace; model selection and ZIP export use Android system document pickers, not additional app-owned screens.
- This is a polish of the existing screen, not a redesign. The existing OLED-first black/dark palette and optional light mode are retained; no catalog design system was installed over the product's existing visual identity.
- The screen is reorganized into four clear surfaces: model, prompt, status and output. The main task is model selection followed by generation. CPU benchmark, appearance and log export move into one secondary-actions menu.
- Content width is capped at 760dp and follows the current app window width. Android system-bar and display-cutout insets are applied to the scroll container.
- Primary controls are at least 48dp high; prompt text remains scalable in sp. Headings are exposed to accessibility services where supported, the prompt field has an accessible name, and status updates use a polite live region.

## Findings and changes

### Serious — resolved

- **Comprehension / first-view choice overload** — Derived from Better Design's first copy check: 44 words and 6 actions (initially serious). The screen now uses 23 words and 3 visible actions; the final comprehension check passed (estimated 14 seconds, heuristic only).
- **Comprehension / unexplained acronym** — Derived from Better Design's first copy check: “LLM” appeared without expansion. The primary heading now uses plain German wording; the final check found no insider term.
- **Comprehension / too many actions** — Derived from Better Design's first copy check: appearance, export, model selection, generation, benchmark and cancellation were all exposed. Benchmark, theme and export now live in the secondary menu; cancellation appears only during a cancellable operation.

### Source-derived accessibility improvements

- **Touch targets** — Interactive buttons have minimum heights of 48dp or 52dp.
- **Prompt field name** — The prompt editor has an explicit content description.
- **Status announcements** — Status changes use a polite accessibility live region.
- **Heading navigation** — The page title and section titles are marked as accessibility headings on API 28+.

No icon-only controls, custom click handlers on non-interactive views, image content or app-owned modal dialogs were found in the current activity source.

## Design-system options considered

The screen already has a product-owned design: an OLED-black dark mode, a restrained blue action color, and a matching light mode. Better Design's catalog matches were therefore not installed, in line with the existing-screen preservation rule. If a future full rebrand is requested, the catalog surfaced these options:

- [Linear Quality](https://better-design.com/design-systems/linear-quality) — refined dark technical UI; gains a more finished engineering aesthetic, loses the current restrained blue palette and adds stronger purple/shadow styling.
- [Cerulean](https://better-design.com/design-systems/cerulean) — compact monochrome command UI; gains precise, dense tool styling, loses some warmth and readability if monospace is overused.
- [Linear](https://better-design.com/design-systems/linear) — familiar developer-tool layout; gains conventional hierarchy, loses the app's existing custom visual identity.
- [Neon](https://better-design.com/design-systems/neon) — futuristic AI styling; gains strong accent visibility, loses restraint through high-intensity green.
- [Monochrome Industrial](https://better-design.com/design-systems/monochrome-industrial) — OLED monochrome; gains maximum black-surface emphasis, loses the current soft, calm typography and blue action cue.

## Checks not run / evidence limits

- **Better Design rendered spacing review:** not run. No current device UI Automator dump or trusted rendered capture was available in this session. No spacing score is claimed.
- **Device screenshots:** not captured here. Dark/light, empty/loaded, running/cancel and 200% font-scale states still require visual verification on a device.
- **Review UI code tool:** unavailable in the connected Better Design tool list; source was inspected directly.
- **Build and APK:** GitHub Actions run for the final UI commit is pending at report creation; no build or APK success is claimed here.
- **Behavioral review:** the existing inference, benchmark, cancellation, picker and ZIP export handlers were preserved, but not exercised by this source-only review.
