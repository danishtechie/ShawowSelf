# ShadowSelf — Setup Guide

## Before building in Android Studio

### 1. Generate the TFLite model (one-time, on your computer)
```bash
cd training/
pip install tensorflow==2.14.0 numpy
python create_base_model.py
# This creates: app/src/main/assets/shadowself_trainable.tflite
```

### 2. Firebase setup (for FCM push alerts)
1. Go to https://console.firebase.google.com
2. Create a new project called "ShadowSelf"
3. Add Android app with package name `com.shadowself`
4. Download `google-services.json` → place in `app/` folder
5. Copy your FCM Server Key into `local.properties`:
   ```
   FCM_SERVER_KEY=your_server_key_here
   ```

### 3. Open in Android Studio
- Open Android Studio → Open → select this `ShadowSelf/` folder
- Wait for Gradle sync to complete
- Connect a physical device (API 26+) — emulators lack real sensor data

### 4. Install and complete onboarding
- Run the app on your device
- Complete the 5-step permission wizard:
  1. Accessibility Service → ShadowSelf → ON
  2. Usage Access → ShadowSelf → Permit
  3. Location → Allow all the time
  4. Camera → Allow
  5. Device Admin → Activate (optional, enables screen lock)

### 5. Wait for training data (7 days)
- Use your phone normally
- WorkManager automatically triggers training when 100+ data windows collected
- Check Settings screen for training progress

### 6. After training completes
- Status orb turns green
- Model is trained on YOUR behaviour
- Any significant deviation triggers: silent selfie → location snap → FCM alert → lockdown

## Notes
- `shadowself_trainable.tflite` is in `.gitignore` (too large for git)
- `google-services.json` is in `.gitignore` (contains secrets)
- All sensor data stays on-device — nothing is sent to any server
