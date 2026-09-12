#!/usr/bin/env bash
set -euo pipefail

fixture_path=/sdcard/DCIM/LifeTimelineTest/synthetic-photo.jpg
adb shell mkdir -p /sdcard/DCIM/LifeTimelineTest
adb push android/app/src/androidTest/assets/synthetic-photo.jpg "$fixture_path"
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$fixture_path"

indexed=false
for attempt in $(seq 1 30); do
  if adb shell content query --uri content://media/external/images/media --projection _id --where "_display_name='synthetic-photo.jpg'" | grep -q '_id='; then
    indexed=true
    break
  fi
  sleep 1
done

if [ "$indexed" != true ]; then
  echo "Synthetic MediaStore image was not indexed."
  exit 1
fi

./android/gradlew --no-daemon -p android connectedDebugAndroidTest
