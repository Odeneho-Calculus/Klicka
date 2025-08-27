---
description: Repository Information Overview
alwaysApply: true
---

# Klicka Android Application Information

## Summary
Klicka is an Android accessibility application that provides voice-controlled click functionality. It uses accessibility services to perform clicks on the screen, overlay services to display visual markers, and voice recognition for hands-free operation.

## Structure
- **app/**: Main application module containing all source code and resources
  - **src/main/java/com/culustech/klicka/**: Core application code
  - **src/main/res/**: Android resources (layouts, strings, drawables)
  - **src/test/**: Unit tests
  - **src/androidTest/**: Instrumentation tests
- **gradle/**: Gradle configuration and wrapper files

## Language & Runtime
**Language**: Kotlin
**Version**: 1.9.24
**Build System**: Gradle 8.10.2
**Package Manager**: Gradle
**Android SDK**:
- **Compile SDK**: 35
- **Target SDK**: 35
- **Min SDK**: 23

## Dependencies
**Main Dependencies**:
- AndroidX Core KTX (1.16.0)
- AndroidX AppCompat (1.7.1)
- Material Components (1.12.0)
- AndroidX Activity KTX (1.9.2)
- AndroidX Lifecycle (2.8.4)

**Development Dependencies**:
- JUnit (4.13.2)
- AndroidX Test JUnit (1.3.0)
- Espresso Core (3.7.0)

## Build & Installation
```bash
./gradlew assembleDebug    # Build debug APK
./gradlew installDebug     # Install debug APK on connected device
./gradlew assembleRelease  # Build release APK
```

## Testing
**Frameworks**:
- JUnit (Unit tests)
- AndroidX Test (Instrumentation tests)
- Espresso (UI tests)

**Test Locations**:
- Unit Tests: app/src/test/java/com/culustech/klicka/
- Instrumentation Tests: app/src/androidTest/java/com/culustech/klicka/

**Run Commands**:
```bash
./gradlew test             # Run unit tests
./gradlew connectedCheck   # Run instrumentation tests
```

## Application Components
**Main Activity**: MainActivity.kt (Entry point)
**Services**:
- ClickAccessibilityService: Performs clicks via accessibility API
- OverlayService: Displays visual markers on screen
- VoiceRecognitionService: Handles voice commands

**Data Management**:
- ClickPointStore: Manages saved click points
- ClickManager: Coordinates click operations

**User Interface**:
- MarkerView: Visual indicator for click points
- SettingsActivity: Configuration interface