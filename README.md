# Anti-NSFW Android Application (Development Halted)

## Overview

This Android application performs **on-device, real-time detection** of potentially Not Safe For Work (NSFW) content displayed on the screen. It utilizes screen capture, efficient change detection, local AI model inference (Nudity and Gender detection), and screen overlays to identify and optionally obscure sensitive content without relying on any backend server.

**Target Audience:** The intended users for this application include Muslims seeking to avoid religiously forbidden (haram) content and individuals generally uncomfortable with the increasing sexualization of online content who wish to filter their view. However, due to the technical challenges outlined below, the application currently cannot fulfill this goal effectively.

**Note:** Development on this project is currently halted due to a significant challenge related to the overlay mechanism, as detailed in the "Current Status & Challenges" section below. This repository is being shared in the hope that others might find the approach interesting or be able to overcome the existing obstacles.

## Features

*   **On-Device Real-time Analysis:** Captures and analyzes screen content directly on the user's device (approx. every 200-300ms).
*   **NSFW Detection:** Uses local TensorFlow Lite models (`NudeDetector.kt`, `GenderDetector.kt`) to identify potentially explicit content and faces/genders.
*   **Efficient Screen Monitoring:** Employs `ScreenCaptureManager` (using Media Projection) and `ScreenChangeDetector` (bitmap hashing) to only process frames when significant visual changes occur, saving battery and resources.
*   **Overlay System:** Displays customizable overlays (`OverlayService.kt`, `OverlayHelper.kt`) on detected areas to obscure them.
*   **Scroll Awareness:** Integrates with `ScrollDetectionService` (Accessibility Service) to pause analysis during active scrolling, improving performance and user experience.
*   **Inter-Service Communication:** Uses Android's `Messenger` API for reliable and efficient communication between the main `ScreenshotService` and the `OverlayService`.
*   **Performance Monitoring:** Tracks and displays basic performance metrics (e.g., inference time) in the `MainActivity`.
*   **User Control:** Allows users to start/stop the monitoring service via `MainActivity` and a persistent notification.

## Key Components

*   **`MainActivity.kt`**: The user interface for managing permissions (Screen Capture, Overlay, Notifications), starting/stopping the service, and viewing performance statistics.
*   **`ScreenshotService.kt`**: The main background service orchestrating the entire process. It manages the screen capture loop, coordinates detection, communicates with the `OverlayService`, and handles the service lifecycle and foreground notification.
*   **`ScreenCaptureManager.kt`**: Handles MediaProjection setup and captures screen content as Bitmaps.
*   **`ScreenChangeDetector.kt`**: Compares consecutive screen captures using hashing to determine if a significant change warrants a full analysis.
*   **`NudeDetector.kt`**: Performs inference using a local TensorFlow Lite model to detect potential nudity, returning bounding boxes.
*   **`GenderDetector.kt`**: Performs inference using a local TensorFlow Lite model (likely for face detection and gender classification), returning bounding boxes.
*   **`OverlayService.kt`**: An independent service responsible for drawing and managing screen overlays based on bounding box data received from `ScreenshotService` via `Messenger`.
*   **`OverlayHelper.kt`**: Assists `OverlayService` in managing the overlay views.
*   **`ScrollDetectionService.kt`**: An Accessibility Service that detects scroll events system-wide and notifies `ScreenshotService` to temporarily pause analysis.
*   **`BitmapPool.kt`**: Likely manages Bitmap objects to reduce memory allocation/garbage collection.
*   **`PerformanceStats.kt`**: Handles saving and loading performance metrics using SharedPreferences.
*   **`StopScreenshotServiceReceiver.kt`**: A `BroadcastReceiver` to handle the stop action from the service notification.
*   **(Other components for Bitmap pooling, performance stats, service control...)**

## How it Works (Intended Workflow)

1.  User grants necessary permissions (Screen Capture, Overlay) via `MainActivity`.
2.  User starts the service from `MainActivity`.
3.  `ScreenshotService` starts, initializes detectors, binds to `OverlayService`, and starts `ScreenCaptureManager`.
4.  The service enters a loop (every ~200-300ms):
    a.  Capture the current screen via `ScreenCaptureManager`.
    b.  Check if scrolling via `ScrollDetectionService`. If yes, pause briefly.
    c.  Compare the new frame with the previous one using `ScreenChangeDetector`. If no significant change, skip to the next iteration.
    d.  If changed, run inference using `NudeDetector` and `GenderDetector` on the captured Bitmap.
    e.  Send the combined list of detected bounding boxes to `OverlayService` via `Messenger` (`MSG_UPDATE_OVERLAYS`).
    f.  Update performance stats.
5.  `OverlayService` receives the bounding boxes and draws/updates overlays on the screen accordingly.

## Current Status & Challenges (Project Halted)

**Background & Motivation:** The current approach using MediaProjection (screen capture) was chosen after exploring other methods. A previous attempt involved using the Xposed Framework to intercept images *before* they were rendered to the screen. While promising, this approach faced significant hurdles in reliably intercepting content within complex views like WebViews and critically, required root access, making it unsuitable for a general audience. The MediaProjection method, while not requiring root, unfortunately led to the feedback loop described below.

**The Overlay Feedback Loop:** The primary challenge that led to halting development is an **overlay feedback loop**:

1.  **Detection:** The app successfully detects NSFW content in a screen region.
2.  **Obscuring:** An overlay is drawn over the detected region by `OverlayService`.
3.  **Re-Analysis:** The *next* screenshot captured by `ScreenCaptureManager` inevitably includes the overlay that was just drawn.
4.  **False Negative:** When this new screenshot (containing the overlay) is analyzed by `NudeDetector`, the original NSFW content is now obscured by the overlay. The detector, therefore, no longer identifies the region as positive.
5.  **Overlay Removal/Flicker:** Consequently, the `OverlayService` might remove the overlay in the next update (since the region is no longer flagged), causing the original content to reappear, leading to a potential detection loop and visual flickering.

**Problem Constraints:**

*   Temporarily hiding overlays *before* taking a screenshot was considered but deemed detrimental to the user experience and the core purpose of immediate obscuring. The overlay needs to be persistent while the content is visible underneath.
*   Finding a way to make the `ScreenCaptureManager` capture the screen *without* including the overlays drawn by its own application's `OverlayService` proved difficult or impossible with standard Android APIs.

This feedback loop prevents the stable and reliable obscuring of detected content. **Overcoming this challenge is the main task required to make this project viable.**

## Technology Stack

*   **Language:** Kotlin
*   **Platform:** Android SDK
*   **Core Components:** Android Services (Foreground, Accessibility), Media Projection API, Messenger API (IPC), TensorFlow Lite (for on-device inference), Canvas (for overlays).
*   **Concurrency:** Kotlin Coroutines
*   **Logging:** Android Logcat (`Log.d`, `Log.e`, etc.)

## Setup & Usage (For Experimentation)

1.  Clone the repository.
2.  Open the project in Android Studio.
3.  Build and run the application on an Android device or emulator.
4.  Grant the required permissions when prompted by the `MainActivity`:
    *   Screen Capture
    *   Draw Over Other Apps
    *   Notifications (Android 13+)
    *   Enable the Accessibility Service (`ScrollDetectionService`) in the device's Settings.
5.  Tap the "Start Service" button in the app. *Observe the potential overlay flickering issue on detected content.*

## Contributing

While active development by the original author has stopped, contributions or ideas on how to solve the overlay feedback loop challenge are welcome. Please open an issue to discuss potential solutions.

## License

(Specify the project's license, e.g., MIT, Apache 2.0, or leave blank if undecided.)
