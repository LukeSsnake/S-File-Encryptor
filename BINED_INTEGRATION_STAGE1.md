# BinEd integration - stage 1

This is a staged integration copy based on S-File-Encryptor 3.0.8 ZIP and BinEd Android 0.2.11.

Included: BinEd Java sources and libsrc, editor layouts/resources, ApplicationContext, HexEditorActivity (renamed from BinEd MainActivity), SettingsActivity, JNA, Material, Flexbox, AndroidX Preference/AppCompat dependencies, and Apache-2.0 license/attribution.

The SFE namespace/applicationId and existing MainActivity are preserved. compileSdk is set to 36. The BinEd Activity uses Android's document intents (SAF). The original file_picker module is retained only because the unmodified BinEd Activity still references its legacy Android-TV fallback dialogs; it is not the normal phone open/save path.

IMPORTANT: This is a source-integration draft, not a verified build. The supplied SFE ZIP used AGP 8.7.3 and Gradle 8.9, while the current working SFE project may have newer build-tool versions. Keep the versions from the current working project when applying these changes. Build was not verified in this environment because no Android SDK is configured here.

The SFE MainActivity has not yet been given a UI entry point for the hex editor. The next stage should add a SAF-backed entry point that launches HexEditorActivity with an ACTION_VIEW content URI and read/write grant flags.
