# Desktop Navigation

## Core Navigation Paradigm
Unlike Android, which relies heavily on deep back-stacks and a bottom navigation bar, VaultPass Desktop uses a flat, wide navigation structure driven by a persistent Sidebar.

## The Sidebar
- **Primary Destinations:** Dashboard, Vault, Security Center, Generator.
- **Secondary Destinations (Bottom of sidebar):** Settings, Recycle Bin, Lock.
- **Behaviour:** 
  - Clicking a destination immediately swaps the main content area.
  - The sidebar never disappears unless the window is extremely narrow, at which point it collapses to icons.

## The Top Bar
- **Purpose:** Contextual actions and global search.
- **Layout:** 
  - Left: (Only if sidebar is collapsed) Hamburger menu.
  - Center: Global Search Bar.
  - Right: Sync Status Icon, Quick Add (+) button, User Profile / App Menu.

## Back Navigation
- Desktop apps rarely need a physical "Back" button for top-level navigation.
- If a user enters a deep flow (e.g., full-screen Import wizard), use a prominent `< Back` breadcrumb at the top left of the main content area, or an "X" to dismiss a modal.
- `Esc` key must always dismiss modals, popovers, and dialogs, effectively acting as the back button.

## Keyboard Navigation
- `Tab` / `Shift+Tab`: Moves focus sequentially through all interactive elements.
- `Up/Down Arrows`: Navigates lists (like the Vault password list).
- `Enter`: Selects the currently focused item.
- `Ctrl+1` through `Ctrl+4`: Quick jump to top-level sidebar destinations.

## Window Behaviour and Multi-Window Support
- **Current State:** Single-window application. The main window maintains its size and position between sessions.
- **Future Multi-Window Support:** The architecture should allow opening a specific password entry in a separate detached window (e.g., clicking a "Pop out" icon) to allow users to reference credentials while keeping the main vault minimized or closed.
