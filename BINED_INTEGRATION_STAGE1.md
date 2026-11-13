# BinEd integration - stage 1

This is a staged integration of BinEd Android 0.2.11 into S-File-Encryptor, based on S-File-Encryptor 3.0.8.

The main BinEd screen was integrated as an additional function of S-File-Encryptor. The original BinEd `MainActivity` was converted into a separate `HexEditorActivity`, allowing the hex editor to coexist with the existing S-File-Encryptor `MainActivity`.

Included: BinEd Java sources and libsrc, editor layouts/resources, ApplicationContext, `HexEditorActivity` (renamed and adapted from the original BinEd `MainActivity`), `SettingsActivity`, JNA, Material, Flexbox, AndroidX Preference/AppCompat dependencies, and Apache-2.0 license/attribution.

## UI integration

The BinEd interface was adapted to match the visual identity of S-File-Encryptor. Colors, texts, labels, and other interface elements were modified for integration with the host application.

Several options from the original BinEd menus were removed or disabled because they are not required for the S-File-Encryptor integration. Only the functions considered relevant to the integrated hex editor were retained.

The core binary editing functionality was kept substantially unchanged. The editor continues to provide the original BinEd functionality for viewing and editing binary data, including saving modified files.

The original BinEd editor code was therefore minimally modified in the areas responsible for binary data editing, while most changes were focused on integration, navigation, appearance, menus, and Android application structure.

## Application structure

The S-File-Encryptor namespace and `applicationId` are preserved. The existing S-File-Encryptor `MainActivity` remains the main application activity.

The original BinEd `MainActivity` was converted into `HexEditorActivity` and adapted to operate as a secondary activity within S-File-Encryptor.

The integrated hex editor uses Android's Storage Access Framework (SAF) for opening and saving files, allowing the user to select files through the standard Android document interface.

The original `file_picker` module is retained because some unmodified BinEd code still references its legacy Android-TV fallback dialogs. It is not the normal phone open/save path.

## Dependencies and licensing

The integration includes the dependencies required by BinEd, including JNA, Material, Flexbox, AndroidX Preference, and AndroidX AppCompat.

BinEd source code is derived from BinEd Android 0.2.11 by the ExBin Project and is licensed under the Apache License, Version 2.0. The integration includes the corresponding Apache-2.0 license and attribution information.

The original S-File-Encryptor source code remains licensed under the MIT License.

## Build configuration

The SFE `applicationId` and existing application structure are preserved. The project uses the build-tool versions from the current working S-File-Encryptor project rather than requiring the versions from the original integration source.

The integration was initially based on an S-File-Encryptor 3.0.8 ZIP and BinEd Android 0.2.11. The supplied SFE ZIP used AGP 8.7.3 and Gradle 8.9; these versions are not intended to override newer versions already present in the working project.

## Integration status

The BinEd hex editor has been integrated into S-File-Encryptor as an additional application function.

The original S-File-Encryptor `MainActivity` remains responsible for the main application interface and functionality, while the adapted `HexEditorActivity` provides the integrated binary editor.

The editor's core file editing functionality has remained largely unchanged from the original BinEd implementation. The principal modifications in this stage concern integration into the S-File-Encryptor application, activity structure, user interface, menus, navigation, and file access.

This document describes the first integration stage and the resulting structure of the integrated BinEd component.
