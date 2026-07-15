---
name: VaultPass Core
colors:
  surface: '#121315'
  surface-dim: '#121315'
  surface-bright: '#38393b'
  surface-container-lowest: '#0d0e10'
  surface-container-low: '#1b1c1e'
  surface-container: '#1f2022'
  surface-container-high: '#292a2c'
  surface-container-highest: '#343537'
  on-surface: '#e3e2e5'
  on-surface-variant: '#c2c6d6'
  inverse-surface: '#e3e2e5'
  inverse-on-surface: '#303033'
  outline: '#8c909f'
  outline-variant: '#424754'
  surface-tint: '#adc6ff'
  primary: '#adc6ff'
  on-primary: '#002e6a'
  primary-container: '#4d8eff'
  on-primary-container: '#00285d'
  inverse-primary: '#005ac2'
  secondary: '#4cd7f6'
  on-secondary: '#003640'
  secondary-container: '#03b5d3'
  on-secondary-container: '#00424e'
  tertiary: '#4edea3'
  on-tertiary: '#003824'
  tertiary-container: '#00a572'
  on-tertiary-container: '#00311f'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#d8e2ff'
  primary-fixed-dim: '#adc6ff'
  on-primary-fixed: '#001a42'
  on-primary-fixed-variant: '#004395'
  secondary-fixed: '#acedff'
  secondary-fixed-dim: '#4cd7f6'
  on-secondary-fixed: '#001f26'
  on-secondary-fixed-variant: '#004e5c'
  tertiary-fixed: '#6ffbbe'
  tertiary-fixed-dim: '#4edea3'
  on-tertiary-fixed: '#002113'
  on-tertiary-fixed-variant: '#005236'
  background: '#121315'
  on-background: '#e3e2e5'
  surface-variant: '#343537'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  title-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.01em
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.03em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  container-margin: 40px
  grid-gutter: 16px
---

## Brand & Style
The design system is engineered for a high-security, high-utility desktop environment. It follows a **SaaS Modernism** aesthetic—a synthesis of Material Design 3 logic with the refined, translucent finishes found in modern desktop operating systems. The brand persona is authoritative, precise, and technologically advanced.

The visual narrative focuses on "The Protected Core." This is achieved through a multi-layered interface strategy:
*   **Mica-inspired Depth:** Subtle background blurs and translucency mimic the feel of native desktop applications.
*   **Precision Tooling:** High-density layouts optimized for power users, utilizing keyboard-centric navigation metaphors.
*   **Bento Grid Structure:** Information is organized into distinct, highly-functional cells that maximize screen real estate without sacrificing clarity.

## Colors
This design system utilizes a deep-space palette to reduce eye strain during long-duration desktop usage. 

*   **Primary (#3B82F6):** A modern, high-vibrancy blue used for critical actions and active states.
*   **Surface Logic:** The base layer starts at `#0B0C0E`. Elevated containers (Bento cells, sidebars) use `#16171A` for standard elevation and `#1C1E21` for interactive or hovered states.
*   **Accent Palette:** Semantic colors are calibrated for high contrast against the dark background, ensuring accessibility and immediate recognition of system status (Success, Warning, Danger, Info).

## Typography
The typography system relies on **Inter** for its exceptional legibility and neutral, geometric character. 

The hierarchy is optimized for desktop density. Headers use tighter letter-spacing and heavier weights to maintain visual impact on large displays. UI labels (Label-MD and Label-SM) are specifically tuned for data-rich environments like password tables and setting panels, ensuring high information density remains readable. Use `label-sm` for category headers and metadata tags.

## Layout & Spacing
The layout follows a **Fluid Bento Grid** philosophy. 

*   **Desktop Structure:** A 12-column grid with a fixed 240px sidebar. Gutters are kept at a consistent 16px to maintain a compact, "pro-tool" feel.
*   **Bento Cells:** Content is encapsulated in modular containers. These containers should span column increments (e.g., 3, 6, or 12) and maintain consistent internal padding of 24px.
*   **Density:** Internal component spacing uses a 4px baseline. Buttons and inputs utilize 8px vertical and 16px horizontal padding to balance comfort with the need for density in a dashboard environment.

## Elevation & Depth
Depth in the design system is communicated through "Material Glass"—a combination of tonal layering and translucency.

1.  **Level 0 (Floor):** `#0B0C0E`. Pure background.
2.  **Level 1 (Card/Cell):** `#16171A` with a 1px border (`#FFFFFF` at 8% opacity). This layer uses a subtle backdrop blur (20px) when positioned over dynamic content.
3.  **Level 2 (Active/Hover):** `#1C1E21` with a slightly brighter 1px border (`#FFFFFF` at 12% opacity).
4.  **Level 3 (Modals/Popovers):** Surface color with a soft, diffused shadow (`y: 8, blur: 24, spread: -4, color: rgba(0,0,0,0.5)`).

Avoid heavy dropshadows on primary UI elements; use border contrast and subtle tonal shifts to define boundaries.

## Shapes
The shape language balances modern approachable design with the structural rigidity of a security tool. 

Standard components (Buttons, Input Fields) use a **12px (rounded-lg)** radius. Larger containers and Bento cards use a **16px (rounded-xl)** radius to create a soft, nested aesthetic. Contextual menus and tooltips use a **8px (rounded-md)** radius for a sharper, more precise feel.

## Components
### Buttons
*   **Primary:** Solid Primary Blue background with white text. High-contrast, no shadow.
*   **Secondary/Ghost:** Subtle white-alpha border (10%) with transparent background. On hover, background shifts to 8% white.

### Inputs & Search
*   **Standard Field:** Dark surface background (`#16171A`) with a 1px border. 
*   **Command Bar (Cmd+K):** A centered, floating input field with a heavy backdrop blur and 2px primary border accent to denote active focus.

### Cards (Bento)
*   Modular containers with 24px internal padding. Title typography should be `title-lg`. Borders are essential to distinguish cells in the dark theme.

### Status Chips
*   Pill-shaped with low-opacity background tints of their respective semantic color (e.g., Success Green at 15% opacity) and high-vibrancy text/icons.

### Data Lists
*   Rows should be 56px high for desktop density. Use 1px horizontal separators (`#FFFFFF` at 4% opacity). Hover states should apply a subtle highlight (`#FFFFFF` at 4% opacity) across the entire row.