# Desktop Interaction Guidelines

Desktop users have access to precision pointing devices (mice/trackpads) and physical keyboards. Interactions should be heavily optimized for speed and power-user workflows.

## Mouse Interactions

### 1. Hover States
- **Discovery:** Hovering over a Vault entry row should reveal quick actions (Copy Username, Copy Password, Edit) on the right edge of the row.
- **Feedback:** All clickable elements (buttons, links, rows) must have a subtle background or border change on hover (e.g., lightening the background by 5% in dark mode).

### 2. Click Behaviors
- **Single Click:** Selects an item. Clicking a Vault row opens its Details Panel. Clicking a "Copy" icon copies to the clipboard.
- **Double Click:** Double-clicking a specific field (like the masked password in the details panel) should immediately copy its contents to the clipboard.

### 3. Right-Click (Context Menus)
- Native OS context menus should be overridden with custom Theme-matching menus.
- **Vault List Context Menu:** Right-clicking a password row should expose:
  - Copy Username
  - Copy Password
  - Edit
  - Move to Recycle Bin
- **Input Field Context Menu:** Standard Cut, Copy, Paste, Select All.

### 4. Drag & Drop (Future Proofing)
- **Import:** Users should be able to drag a `.json` or `.vpex` file directly into the app window to trigger the Import flow.

## Keyboard Shortcuts
Power users rely heavily on shortcuts. Implement the following globally:
- `Ctrl + F` (or `Cmd + F`): Focus the global search bar.
- `Ctrl + N` (or `Cmd + N`): Open the "Add Password" modal.
- `Ctrl + L` (or `Cmd + L`): Instantly lock the vault.
- `Ctrl + C` (or `Cmd + C`): When a Vault row is selected, copies the password. `Ctrl + Shift + C` for username.
- `Esc`: Close dialogs, clear search, dismiss details panel.

## Search, Filtering, and Sorting
- **Search:** Should be instantaneous (live filtering) as the user types.
- **Sorting:** Columns in the Master list should be sortable by clicking headers (e.g., Title A-Z, Date Modified, Usage Frequency).

## Clipboard Behaviour
- Whenever sensitive data is copied, show a brief transient Snackbar ("Password Copied").
- **Security:** Maintain the Android app's clipboard clearing functionality. If the user copies a password, automatically purge the OS clipboard after the configured time (e.g., 30 seconds), provided the user hasn't copied something else in the meantime.

## Confirmation Dialogs
- **Destructive Actions:** (e.g., "Delete Forever") require explicit confirmation via a modal dialog. The default focused button MUST be "Cancel" to prevent accidental `Enter` key presses from deleting data.
