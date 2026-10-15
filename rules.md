# DisasterConnect Coding Rules & Standards

This document outlines the coding standards and project rules for the DisasterConnect Kotlin project.

## 1. Project Overview
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Map Engine**: MapLibre GL Android SDK
- **Location Services**: Google Play Services Fused Location Provider (`FusedLocationProviderClient`)
- **Build System**: Gradle with Kotlin DSL (`.gradle.kts`)

## 2. Formatting & Style Guidelines
A unique visual style is maintained in this project to enhance readability:

- **Vertical Spacing**: Use aggressive vertical spacing. Almost every logical line or parameter in a function call should be on a new line with a blank line separating it from the next.
- **Section Headers**: Use formal comment blocks to separate major sections of a file:
  ```kotlin
  // =========================================================
  // MAJOR SECTION NAME
  // =========================================================
  ```
- **Sub-section Headers**: Use dashed comment blocks for smaller logical groupings:
  ```kotlin
  // ---------------------------------------------------------
  // Sub-section Name
  // ---------------------------------------------------------
  ```
- **Indentation**: Standard 4-space indentation.

## 3. UI Development (Compose)
- **Activity Structure**: Activities should handle permission requests and high-level navigation.
- **State Management**: Use `mutableStateOf` with `by` delegates in Activities for UI reactivity.
- **Theme**: Always wrap Compose UIs in `DisasterConnectTheme`.
- **Naming**: Composable functions should use PascalCase (e.g., `DisasterConnectHome`).

## 4. Location & Permissions
- **Fused Location**: Always use `FusedLocationProviderClient` for location updates.
- **Permission Flow**: 
    - Use `ActivityResultContracts.RequestMultiplePermissions()` for location access.
    - Check both `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`.
    - Provide a clear `PermissionScreen` when access is denied.

## 5. Network & Connectivity
- **Connectivity Monitoring**: Use `ConnectivityManager` to check for active networks.
- **Validation**: Ensure `NET_CAPABILITY_VALIDATED` is checked to confirm actual internet access, not just a connection to a router.

## 6. Maps (MapLibre)
- **Initialization**: Call `MapLibre.getInstance(this)` before creating `MapView`.
- **Lifecycle**: Explicitly forward all Activity lifecycle methods (`onStart`, `onResume`, `onPause`, etc.) to the `MapView` instance.

## 7. Resource Management
- **Strings**: User-facing text should be descriptive and clear, reflecting the emergency nature of the app.
- **Icons**: Use Emoji symbols (e.g., 📍, ⚠️) sparingly for visual cues in the UI where appropriate.
