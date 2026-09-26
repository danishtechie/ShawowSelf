# ShadowSelf

ShadowSelf is an Android app designed to learn a person’s normal phone behavior and spot unusual activity that may suggest someone else is using the device.

Instead of relying on a single signal, it combines several behavioral patterns into a personal baseline: typing rhythm, touch behavior, app usage, motion, unlock patterns, and contextual signals such as location and ambient environment. Once enough data is collected, it uses a local TensorFlow Lite model to compare current usage against that learned pattern.

The goal is simple: detect suspicious behavior early and respond quietly before damage is done.

## Why this project exists

Most phone security tools focus on protecting the device at the login or app layer. ShadowSelf takes a different approach:

- it watches how the owner normally uses the phone
- it learns the pattern over time
- it flags behavior that looks off
- it can capture evidence and trigger a defensive response

This makes it useful as a personal behavioral security system rather than a typical app utility.

## What the app does

ShadowSelf continuously collects signals in the background and assembles them into short behavioral windows. These windows are then analyzed for anomalies.

### Behavioral signals it monitors

- Typing rhythm and inter-key timing
- Touch and swipe patterns
- Accelerometer and motion behavior
- App usage patterns and category activity
- Unlock behavior
- Location context such as home/work/other environments
- Ambient conditions such as device context and environment cues

### What happens when it detects something unusual

When the app finds a significant deviation from the owner’s normal behavior, it can:

- log the anomaly locally
- capture a silent front-camera selfie
- store evidence locally
- attach location information
- notify via Firebase push alerts
- optionally trigger a device lockdown or screen lock

## How it works

The app runs a foreground monitoring service that collects data over time. It stores raw sensor windows locally, extracts feature vectors, and trains or refines a TensorFlow Lite model on-device.

The system is designed to improve with regular use:

1. The app observes the owner’s behavior
2. It collects enough data windows for training
3. It builds a model from that behavioral profile
4. It checks future activity against the trained baseline
5. It reacts when the behavior becomes suspicious

## Project highlights

- Built with Kotlin and Jetpack Compose
- Uses Hilt for dependency injection
- Stores local behavior data with Room
- Uses WorkManager for background training tasks
- Runs local on-device inference via TensorFlow Lite
- Uses Firebase Cloud Messaging for alert delivery
- Supports a guided onboarding flow for required permissions

## Privacy and data handling

This project is designed with privacy in mind:

- sensor data is stored on-device
- the app is intended to process behavior locally
- some data is intentionally reduced to feature-level patterns rather than raw text or full personal data
- incident evidence is kept locally and encrypted where relevant

The project setup guide explicitly notes that the app is designed to avoid sending normal usage data to a server.

## Requirements

To build and run this app, you need:

- Android Studio
- JDK 17
- a physical Android device with API 26+
- Android permissions granted during onboarding
- Firebase setup for push alert support
- a generated TensorFlow Lite model for the training flow

## Setup

For a full setup and build guide, see [README_SETUP.md](README_SETUP.md).

### Quick overview

1. Generate the base TFLite model in the training folder
2. Add Firebase configuration and the required service file
3. Open the project in Android Studio
4. Grant the app the required permissions during onboarding
5. Let the app collect usage data over time
6. Train and refine the local model

## Important note

This project is a behavioral security prototype and is not a general-purpose consumer app in the same sense as a banking or mainstream utility app. It relies on sensitive access to system permissions and is intended for serious, privacy-conscious, owner-controlled device monitoring.

## License

This project is currently intended for local development and research use. Please check the repository status before publishing or distributing it externally.

## Contributing

Contributions are welcome if they improve:

- detection quality
- privacy protections
- code clarity
- reliability of the training pipeline
- background monitoring stability

If you are contributing, keep the project’s focus on ethical, on-device behavioral monitoring and privacy-aware defensive design.
