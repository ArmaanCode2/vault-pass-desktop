# Desktop Future Expansion

VaultPass is designed as an offline-first password manager, but the architecture and UI must seamlessly accommodate future syncing and integration features without requiring a fundamental redesign.

## 1. Synchronization (LAN, Bluetooth, Cloud)
- **Insertion Point:** A dedicated "Sync" tab should be added to the Settings view.
- **UI Indication:** Add a small connection status icon (Cloud with a checkmark, or two computers linked) to the Top Bar next to the User Profile icon. Clicking it drops down a popover detailing the last sync time and connected devices.
- **Conflict Resolution:** If a sync conflict occurs, a non-blocking banner should appear at the top of the Vault view: "3 conflicts require your review." Clicking it opens a dedicated conflict resolution dialog.

## 2. Browser Extension Integration
- **Insertion Point:** A "Browser Integration" tab in Settings to pair the desktop app with the extension (via WebSockets or Native Messaging).
- **UI Indication:** A small browser icon in the Top Bar indicating the connection status.
- **Approval Flow:** When the browser requests credentials, a system-level notification (or an always-on-top mini window) should appear asking the user to approve or deny the autofill request.

## 3. Windows Hello / Biometric Login
- **Insertion Point:** Triggered automatically upon app launch in the Lock Screen (similar to the Biometric prompt on Android).
- **Settings:** Managed in the "Security" tab of the Settings pane.
- **UI:** A subtle "Unlock with Windows Hello" button below the master password input on the Lock Screen.

## 4. System Tray (Background Running)
- **Behaviour:** Closing the main window should minimize the app to the System Tray rather than killing the process (configurable in Settings).
- **UI:** Right-clicking the System Tray icon should reveal a context menu: "Lock Vault", "Open VaultPass", "Generate Password", "Quit".

## 5. Multi-Vault Support
- **Insertion Point:** If users need multiple distinct vaults (e.g., Personal and Work), the User Profile icon in the Top Bar should serve as a Vault Switcher dropdown.
- **UI:** The Lock Screen would need a dropdown to select which vault to decrypt.

## 6. Plugins and Extensibility
- **Insertion Point:** A "Plugins" or "Extensions" section in the Sidebar (placed towards the bottom).
- **Layout:** Should follow the Master-Detail pattern to manage installed plugins and their specific configurations.
