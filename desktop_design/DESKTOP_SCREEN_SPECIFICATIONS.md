# Desktop Screen Specifications

## Lock Screen
- **Purpose:** Authenticate the user and decrypt the vault.
- **Layout:** Centered modal card on the screen. Background is either a subtle gradient, dark blur, or solid theme background. 
- **Sections:** Logo/Branding (top), Master Password input (center), Biometric / Windows Hello trigger button (if supported), Unlock button. Privacy policy link (subtle footer).
- **Desktop Improvement:** Auto-focus the password field immediately upon app launch. Support `Enter` key to submit.

## Dashboard
- **Purpose:** High-level overview of vault health and quick access to favorites.
- **Layout:** 
  - Left: Sidebar.
  - Main: Top section contains Security Score summary cards. Bottom section contains a grid of Favorite entries.
- **Desktop Improvement:** Utilize a grid layout for cards that reflows based on window width.

## Vault
- **Purpose:** Browse, search, and manage all passwords.
- **Layout:** Master-Detail Split View.
  - Left Sidebar: App Navigation.
  - Middle Column (Master): Scrollable list of all passwords. Sticky search/filter bar at the top.
  - Right Column (Detail): When an entry is selected, its full details, custom fields, and edit/delete actions appear here.
- **Expected Workflow:** User clicks Vault -> Scrolls or searches middle column -> Clicks entry -> Clicks "Copy Password" in the right column.
- **Desktop Improvement:** Master-Detail view entirely eliminates the need for screen navigation when browsing passwords.

## Add / Edit Password
- **Purpose:** Create or modify a vault entry.
- **Layout:** Replaces the Right Column (Detail) in the Vault view, or opens as a large modal Dialog.
- **Sections:** Title, Username, Password (with generator button), Website, Category dropdown, Notes textarea, Custom Fields (key/value dynamic list).
- **Desktop Improvement:** Use dense form fields allowing the user to `Tab` quickly through the entire form without scrolling.

## Settings
- **Purpose:** Configure app behavior, security, and theme.
- **Layout:** Two-pane layout.
  - Left: Settings Categories (Security, UI, Sync, About).
  - Right: The configuration options for the selected category.
- **Desktop Improvement:** Eliminates deep navigation stacks. All settings are max two clicks away.

## Security Center
- **Purpose:** Analyze password health (Weak, Reused, Missing).
- **Layout:** A wide dashboard. Top section shows a large visual donut chart or score gauge. Below it, three columns or a tabbed list filtering passwords by weakness category.
- **Expected Workflow:** User clicks "Fix" next to a weak password, immediately opening the Edit pane.

## Password Generator
- **Purpose:** Generate secure strings.
- **Layout:** Can be accessed via the Sidebar as a standalone tool, or as a popover/inline tool within the Add/Edit screen.
- **Sections:** Large generated text display, sliders for Length (8-128), toggles for Uppercase, Lowercase, Numbers, Symbols.

## Recycle Bin
- **Purpose:** Recover deleted items or permanently purge them.
- **Layout:** Similar to the Vault view but without the detailed edit panel. A straightforward list view with columns: Title, Deleted Date, Time Remaining, Actions (Restore / Delete).

## Import / Export
- **Purpose:** Move data in and out of VaultPass.
- **Layout:** A dedicated view within Settings or a standalone modal.
- **Sections:** 
  - Import: Drag-and-drop zone for .vpex, .json, or .txt files.
  - Export: Format selector, Master password verification prompt, "Save As..." system file picker trigger.

## Privacy Policy & About
- **Purpose:** Transparency and legal/version info.
- **Layout:** Simple, readable text document view. Opens in the default system web browser when external links are clicked.
