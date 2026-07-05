# Desktop Component Library

## Sidebar (Navigation)
- **Purpose:** Primary application navigation.
- **Behaviour:** Fixed to the left side. Can collapse into an icon-only "rail" mode.
- **States:** Active tab highlighted with a subtle vertical line or distinct background color. Hover state lightly highlights the row.
- **Sizing:** `240px` wide expanded, `64px` wide collapsed.

## Top Bar (App Bar)
- **Purpose:** Window controls, global search, and high-level status (sync, lock).
- **Behaviour:** Sticky at the top. Contains a wide search bar in the center.
- **Sizing:** `48px` to `56px` height (thinner than Android's 64px).

## Search Bar
- **Purpose:** Rapid vault querying.
- **Behaviour:** Auto-focuses when pressing `Ctrl+F`. Shows a clear 'X' button when text is entered.
- **Interactions:** Use up/down arrow keys to navigate live search results in a dropdown popover.

## Password Row (List Item)
- **Purpose:** Display a single vault entry in the list view.
- **Sizing:** `40px` height (dense).
- **States:** 
  - Default: Shows Favicon, Title, Username.
  - Hover: Background highlights, reveals quick-action icon buttons (Copy Username, Copy Password, Edit) aligned to the right.
  - Selected: Distinct background, indicates this item is active in the Details Panel.

## Password Details Panel (Split View)
- **Purpose:** Display the full contents of a selected Vault Entry.
- **Layout:** Appears on the right side of the screen when a row is selected.
- **Behaviour:** Password fields show bullet points `••••••••`. Clicking the field or a "Copy" button places it on the clipboard. An "Eye" icon toggles visibility.

## Dialogs (Modals)
- **Purpose:** Blocking interactions (Master Password entry, Delete Confirmation).
- **Behaviour:** Centers on screen with a blurred or darkened background overlay.
- **Interaction:** Can be dismissed by pressing `Esc` or clicking the overlay (unless it's the Lock Screen).

## Input Fields
- **Purpose:** Data entry (Add/Edit password).
- **States:**
  - Default: `1px` border.
  - Focus: `2px` Primary color border, subtle shadow.
  - Error: Red border, error text below.
- **Sizing:** `36px` height for standard text inputs.

## Buttons
- **Purpose:** Triggering actions.
- **Styles:** Primary (Solid background), Secondary (Outlined), Tertiary (Text only).
- **States:**
  - Hover: Subtle brightness increase.
  - Disabled: Lowered opacity (38%), unclickable.

## Snackbars (Toast equivalents)
- **Purpose:** Transient success/error messages (e.g., "Password Copied").
- **Behaviour:** Floats at the bottom-center or bottom-right of the window. Disappears after 3 seconds.

## Status Badges
- **Purpose:** Indicate security health (Weak, Reused, Missing).
- **Sizing:** Small, rounded rectangles (`4px` radius) with capitalized, `10sp` bold text.

## Recycle Bin Card
- **Purpose:** Show items pending permanent deletion.
- **Interactions:** Hover reveals "Restore" and "Delete Forever" buttons. Displays a countdown (e.g., "5 days remaining").
