# ShadowSelf

ShadowSelf is an Android behavioral security app designed to learn how a person normally uses their phone and detect unusual activity that may suggest unauthorized access.

It combines several signals to build a personal activity baseline, including typing rhythm, touch behavior, motion, app usage, unlock patterns, and contextual data such as location and ambient environment. Once enough data is collected, the app uses a local TensorFlow Lite model to compare current behavior against that learned pattern.

The goal is to detect suspicious usage early and respond quietly before the device is misused.

## What it does

ShadowSelf continuously collects behavioral signals in the background and groups them into short windows for anomaly detection.

### Signals monitored

- Typing timing and rhythm
- Touch and swipe behavior
- Motion and accelerometer patterns
- App usage activity
- Unlock behavior
- Location context and environment cues

### Response when anomalies are detected

When usage deviates significantly from the owner’s normal pattern, the app can:

- log the anomaly locally
- capture a silent front-camera selfie
- store evidence locally
- attach location context
- notify via Firebase push alerts
- optionally trigger a device lockdown

## How it works

The app runs a foreground monitoring service that continuously gathers sensor and usage signals. These are saved into local windows, converted into feature vectors, and used to train or refine an on-device model.

Over time, the model becomes a behavioral fingerprint of the owner’s normal usage and flags suspicious deviations.

## Tech stack

- Kotlin
- Jetpack Compose
- Hilt
- Room
- WorkManager
- TensorFlow Lite
- Firebase Cloud Messaging
- Android system permissions and device admin features

## Privacy and security

This project is designed around local, on-device processing. It stores behavior data locally and reduces many signals into feature-level patterns rather than raw user content. It is built for privacy-conscious personal monitoring rather than cloud-based analytics.

## Setup

For the full local setup instructions, see [README_SETUP.md](README_SETUP.md).

## Important note

This app relies on sensitive system permissions and is intended for owner-controlled, privacy-focused behavioral monitoring. It is not a general consumer utility app and should be treated as a security prototype or experimental project.

## License

This project is intended for local development and research use. Please review repository usage and distribution rights before publishing or distributing externally.
