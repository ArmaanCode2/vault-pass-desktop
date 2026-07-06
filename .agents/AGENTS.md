
## Implementation Philosophy (VaultPass)

1. **Design for Phase 4 Today:** Every class, interface, and architectural decision should be designed as if it will remain in the production application permanently. 
2. **No Placeholder Architecture:** Avoid fake repositories, temporary managers, duplicate interfaces, or placeholder abstractions. Create the final production architecture now. Temporary *implementations* of permanent interfaces are acceptable, but temporary *architecture* is not.
3. **Every Implementation Must Satisfy:**
   - Production quality
   - Offline-first
   - Privacy-first
   - Security-first
   - Future Kotlin Multiplatform compatibility
   - Android compatibility
   - Future LAN synchronization compatibility
   - Future migration compatibility
4. **Post-Milestone Requirement:** After each milestone, explain:
   - Which parts of the implementation are permanent.
   - Which parts are temporary implementations only.
   - Why the architecture will not require refactoring during future phases.

