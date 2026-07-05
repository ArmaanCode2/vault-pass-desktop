# Desktop Animations

## Animation Philosophy
Animations in VaultPass Desktop should be subtle, professional, and extremely fast. A password manager is a utility tool; animations should never make the user wait. 
- **Duration:** Most animations should take between `75ms` and `150ms`.
- **Easing:** Use standard ease-out curves (deceleration) for incoming elements, and ease-in curves (acceleration) for outgoing elements.

## Page Transitions
- **Desktop Paradigm:** Do NOT use the heavy sliding left/right transitions common in mobile apps.
- **Implementation:** When switching between top-level tabs (e.g., Vault to Settings), use a rapid cross-fade (`100ms`). 
- Master-Detail pane updates (e.g., clicking a new password row) should update instantly without animation to maximize perceived performance.

## Dialog Animations
- **Entry:** Fade in the background scrim (`150ms`). The dialog itself should fade in and scale up slightly (from `95%` to `100%`) over `150ms` ease-out.
- **Exit:** Fade out and scale down to `95%` over `100ms` ease-in.

## Hover Animations
- Buttons, links, and list rows should transition their background colors over `75ms` when hovered. This provides a feeling of tactile responsiveness without feeling sluggish.

## Loading and Progress Indicators
- **Determinate:** When exporting/importing large vaults, use a thin, primary-colored progress bar at the very top of the window (under the title bar).
- **Indeterminate:** Use a minimal, smooth spinning ring (not a heavy, chunky spinner). 
- Avoid showing loading spinners for local database reads. If local reads are taking long enough to require a spinner, the query needs to be optimized.

## Reduced Motion
- Always respect the OS-level "Reduce Motion" or "Disable Animations" accessibility preference. If enabled, all cross-fades and scaling animations should instantly snap to their final states.
