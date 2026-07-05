# Desktop UI Guidelines

## Design Philosophy
VaultPass Desktop follows an "Offline-First, Privacy-Centric, and Professional" design philosophy. The UI must feel highly secure, responsive, and invisible—getting out of the user's way while providing lightning-fast access to credentials.

## Product Identity
- **Feeling:** Secure, heavy, locked-down, yet modern and snappy.
- **Visual Style:** Minimalist. No unnecessary illustrations. Data-dense but readable.

## Core Visual Metrics
- **Corner Radius:** Use `8dp` to `12dp` for cards, dialogs, and panels. Avoid the heavy `24dp` or `32dp` pill shapes from Android; desktop requires tighter, sharper corners to maximize screen real-estate.
- **Spacing:** Use a standard `8px` grid. 
  - `16px` for internal padding.
  - `24px` or `32px` for section margins.
- **Elevation & Shadows:** 
  - Use subtle drop shadows for floating elements (dialogs, context menus, tooltips).
  - Use borders (`1px` solid, low opacity) for structural separation (e.g., between the sidebar and the main content area) instead of heavy drop shadows, mimicking native desktop OS feels.

## Typography Hierarchy
- **Font Family:** Inter or Roboto (sans-serif, highly legible).
- **H1 (Page Title):** `24sp`, Bold, High Contrast.
- **H2 (Section Header):** `16sp`, Semi-Bold, Medium Contrast.
- **Body:** `14sp`, Regular (for list items, settings).
- **Caption/Metadata:** `12sp`, Regular, Low Contrast.
- **Monospace:** Use `JetBrains Mono` or `Fira Code` strictly for displaying passwords, keys, and technical data.

## Desktop Layout Philosophy
- **Widescreen First:** Unlike Android's vertical scroll, Desktop must utilize horizontal space. Employ Two-Pane (Split-View) or Three-Pane layouts.
- **Information Density:** Desktop users expect to see more data at once. List rows should be compact (`40px` to `48px` height) compared to touch-friendly mobile rows (`64px`+).

## Responsive Behaviour
- **Sidebar:** Should be resizable or collapsible into a slim icon-only rail when the window is narrow.
- **Main Content:** Grids should reflow based on available width. Split-views should stack if the window is resized below `800px`.

## Window Sizing Recommendations
- **Default Minimum Window Size:** `800px` (Width) x `600px` (Height).
- **Ideal Starting Size:** `1024px` x `768px`.
- **Maximize State:** Supported. Content should center or expand gracefully with max-width constraints on readable text blocks (e.g., max `800px` width for settings panels to prevent eye strain).
