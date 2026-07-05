# Desktop Theme

## Theme Philosophy
The desktop theme is an adaptation of Material 3, hardened for desktop environments. It must support both Light and Dark modes, with a strong emphasis on Dark mode as the default "hacker/secure" aesthetic.

## Color Palette

### 1. Primary Colors
- **Primary:** The accent color chosen by the user (Blue, Green, Purple, Orange, etc.). Should be vibrant in dark mode (e.g., `Blue 400`) and slightly muted in light mode (e.g., `Blue 600`).
- **On-Primary:** High contrast text on primary buttons (White or very dark gray).

### 2. Surface & Background Colors
- **Background (App Window):** 
  - Dark Mode: `#0F1115` (Deep, near-black blue/gray).
  - Light Mode: `#F8F9FA` (Clean, off-white).
- **Surface (Cards, Sidebar, Dialogs):**
  - Dark Mode: `#1A1C23` (Slightly elevated from background).
  - Light Mode: `#FFFFFF` (Pure white).
- **Surface Variant (Hover states, subtle highlights):**
  - Dark Mode: `#252830`
  - Light Mode: `#E9ECEF`

### 3. Semantic Colors
- **Error (Weak Passwords, Deletions):** `#FF5252` (Dark) / `#D32F2F` (Light).
- **Success (Strong Passwords, Copied):** `#4CAF50` (Dark) / `#388E3C` (Light).
- **Warning (Medium Passwords, Overwritten):** `#FFC107` (Dark) / `#F57C00` (Light).

### 4. Text & Typography Colors
- **High Contrast (Titles, primary text):** `#FFFFFF` (Dark) / `#212121` (Light).
- **Medium Contrast (Subtitles, secondary info):** `#B0B3B8` (Dark) / `#5F6368` (Light).
- **Low Contrast (Disabled, placeholders):** `#5C6066` (Dark) / `#9E9E9E` (Light).

## Contrast Requirements
- Text against its immediate background MUST meet WCAG 2.1 AA (4.5:1 for normal text, 3.1 for large text).
- Avoid low-contrast gray-on-gray for critical information like passwords or security scores.

## Material 3 Adaptations
- **Dynamic Color:** If supported by the OS (e.g., Windows 11 accent color), seamlessly map it to the Primary color token.
- **Tonal Elevation:** Instead of using heavy drop shadows, use M3's tonal elevation (lightening the surface color slightly) to indicate depth on Desktop.
- **Borders:** Use `1px` borders with `#FFFFFF` at `10%` opacity (Dark Mode) to delineate panels.
