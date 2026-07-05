# Desktop Implementation Prompts

The following prompts are designed to be fed sequentially to an AI coding assistant to build the VaultPass Desktop application step-by-step. Each prompt is isolated to a specific milestone.

---

### Prompt 1: Project Setup and App Shell
**Objective:** Set up the Compose Desktop project, implement the base UI themes, and construct the application shell (Sidebar + Top Bar).
**Instructions:**
1. Initialize a new Kotlin Multiplatform (or Compose Desktop) project.
2. Implement the color palettes, typography, and spacing defined in `DESKTOP_THEME.md` and `DESKTOP_UI_GUIDELINES.md`.
3. Build the persistent Sidebar (collapsible) and Top Bar.
4. Implement basic routing logic to switch between empty dummy screens (Vault, Settings, Generator).
**Constraints:**
- MUST NOT implement any database logic.
- MUST NOT implement the actual screen contents (use placeholders).
- MUST NOT implement authentication.

---

### Prompt 2: Domain and Data Layer Porting
**Objective:** Integrate the shared KMP logic and establish the desktop persistence layer.
**Instructions:**
1. Import the shared modules (`domain/models`, `domain/security`, `crypto`).
2. Implement the desktop-specific `SettingsRepository` using KMP DataStore (or Java Preferences).
3. Implement the desktop-specific `VaultDao` using SQLDelight or Room KMP.
4. Port the `VaultRepository` to wire the database and crypto layers together.
**Constraints:**
- MUST NOT build any UI components.
- MUST NOT alter the shared business logic interfaces.

---

### Prompt 3: Authentication and Lock Screen
**Objective:** Secure the app by implementing the Lock Screen and master password flow.
**Instructions:**
1. Build the Lock Screen UI as specified in `DESKTOP_SCREEN_SPECIFICATIONS.md`.
2. Connect the Lock Screen to the `SettingsRepository` and `CryptoManager` to derive the KEK and unwrap the DEK.
3. Implement the Auto-Lock timer logic (locking the app after X minutes of inactivity).
**Constraints:**
- MUST NOT implement biometric unlock (Windows Hello) yet.
- MUST NOT build the vault view.

---

### Prompt 4: The Vault and Master-Detail View
**Objective:** Build the core password management interface.
**Instructions:**
1. Implement the Master-Detail split view for the Vault screen.
2. Build the Dense Password Row component for the list (left pane).
3. Build the Password Details Panel (right pane) with masked password fields and copy buttons.
4. Wire the view to the `VaultRepository` to display decrypted entries.
5. Implement the live-search filtering.
**Constraints:**
- MUST NOT implement the Add/Edit functionality yet.
- MUST NOT implement the Recycle Bin.

---

### Prompt 5: Data Mutation (Add/Edit/Delete)
**Objective:** Allow users to modify their vault.
**Instructions:**
1. Build the Add/Edit form layout (Title, Username, Password, Custom Fields).
2. Wire the form to the `VaultRepository`'s insert/update functions.
3. Implement the soft-delete functionality (Move to Recycle Bin).
4. Implement input validation and error states.
**Constraints:**
- MUST NOT implement the Password Generator tool.

---

### Prompt 6: Utilities and Settings
**Objective:** Complete the application features.
**Instructions:**
1. Build the Password Generator UI and wire it to a secure random generator.
2. Build the Security Center dashboard using the shared `SecurityAnalyzer`.
3. Build the Recycle Bin view and wire the restore/permanent delete functions.
4. Build the two-pane Settings screen to manage Theme, Auto-lock timer, and KDF iterations.
**Constraints:**
- MUST NOT implement Import/Export.

---

### Prompt 7: Import / Export and Polish
**Objective:** Finalize data portability and UX polish.
**Instructions:**
1. Build the Import/Export UI.
2. Implement desktop file picker dialogues.
3. Wire the shared JSON/VPEX generation and parsing logic.
4. Implement global keyboard shortcuts (`Ctrl+F`, `Ctrl+N`).
5. Ensure clipboard auto-clearing works via the desktop OS clipboard APIs.
**Constraints:**
- MUST NOT implement network syncing.
