# Desktop Accessibility Guidelines

VaultPass Desktop must be usable by everyone, regardless of physical or visual impairments. The UI must adhere to WCAG 2.1 AA standards as a baseline.

## Keyboard-Only Navigation
- The entire application MUST be fully usable without a mouse.
- **Focus Rings:** When navigating via keyboard (`Tab`), elements must display a high-contrast focus ring (e.g., a `2px` solid primary-color outline offset by `2px`). This ring should NOT appear when clicking with a mouse (use focus-visible heuristics).
- **Focus Order:** Must follow a logical, Left-to-Right, Top-to-Bottom flow.
  1. Top Bar (Hamburger -> Search -> Profile)
  2. Sidebar Links
  3. Main Content Area (List -> Details Panel)

## Screen Reader Compatibility
- Use proper semantic roles for all custom UI elements.
- **ARIA Labels:** Icon-only buttons (like the 'Copy' icon or 'Eye' visibility toggle) MUST have descriptive accessible names (e.g., `aria-label="Copy password to clipboard"`).
- **Live Regions:** Status updates (e.g., "Vault Locked", "Password Copied", search result counts changing) should be announced via assertive/polite live regions.
- Masked passwords should not be read aloud by screen readers unless explicitly unmasked by the user.

## Sizing and Hit Targets
- While desktop allows for denser UI, minimum clickable areas must still be respected.
- **Minimum Hit Target:** `32x32 pixels` for mouse interactions. If a visual icon is `16x16`, ensure its padding creates a `32px` clickable bounding box.

## Contrast
- Text and essential icons must maintain a `4.5:1` contrast ratio against their backgrounds.
- Avoid using color alone to convey meaning. For example, a weak password should not just be red; it should also have an explicit "Weak" text label or an alert icon.

## Accessibility Shortcuts
- Provide a "Skip to Content" hidden link that becomes visible when tabbing into the application, allowing keyboard users to bypass the sidebar navigation.
