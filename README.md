# Klicka

Klicka is an Android accessibility application that enables voice-controlled click functionality for users who need hands-free operation of their devices.

## Features

- **Voice-Controlled Clicks**: Perform clicks on your screen using voice commands
- **Visual Markers**: See where clicks will be performed with on-screen markers
- **Accessibility Integration**: Seamlessly works with Android's accessibility framework
- **Customizable Settings**: Configure the application to suit your needs

## Requirements

- Android 6.0 (Marshmallow, API 23) or higher
- Permissions:
  - Accessibility Service
  - Display over other apps
  - Microphone access

## Installation

1. Download the latest APK from the releases section
2. Install the APK on your Android device
3. Follow the on-screen instructions to grant necessary permissions

## Building from Source

### Prerequisites

- Android Studio Iguana or newer
- JDK 11 or higher
- Android SDK with API level 35

### Build Instructions

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/klicka.git
   ```

2. Open the project in Android Studio

3. Build the application:
   ```bash
   ./gradlew assembleDebug
   ```

4. Install on a connected device:
   ```bash
   ./gradlew installDebug
   ```

## Usage

1. Launch the Klicka app
2. Enable the accessibility service when prompted
3. Grant overlay permission when requested
4. Use voice commands to control clicks:
   - "Click here" - Performs a click at the current marker position
   - "Move up/down/left/right" - Moves the marker in the specified direction
   - "Save point" - Saves the current marker position for future use

## Testing

Run unit tests:
```bash
./gradlew test
```

Run instrumentation tests (requires a connected device):
```bash
./gradlew connectedCheck
```

## License

[Insert your license information here]

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request