[![Build](https://github.com/LukeSsnake/S-File-Encryptor/actions/workflows/build.yml/badge.svg)](https://github.com/LukeSsnake/S-File-Encryptor/actions/workflows/build.yml)
[![Google Play](https://img.shields.io/badge/Google%20Play-Download-414141?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.lukestudio.fileencryptor2)

# S-File-Encryptor

S-File-Encryptor is an Android application that combines local file encryption, metadata removal, secure file deletion, SHA-256 hash verification, and an integrated hexadecimal editor.

All file operations are performed locally on the device.

<img width="1080" height="2400" alt="S-File-Encryptor screenshot" src="https://github.com/user-attachments/assets/9b3479d6-a08b-4413-835e-4339c3207fc5" />

## Download

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.lukestudio.fileencryptor2">
    <img
      src="https://play.google.com/intl/en_us/badges/static/images/badges/pt-br_badge_web_generic.png"
      alt="Available on Google Play"
      height="80">
  </a>
</p>

Latest APK: [GitHub Releases](https://github.com/LukeSsnake/S-File-Encryptor/releases).

## Features

S-File-Encryptor provides a collection of tools for file privacy, security, integrity verification, and binary inspection.

### File Encryption and Decryption

- Password-based file and folder encryption.
- AES-256-GCM encryption with Argon2id key derivation (V2).
- Backward compatibility with legacy V1 encrypted files.
- Authentication verification before decrypted data is saved.
- Biometric authentication support.
- Streaming I/O for large files.

### Metadata Removal

- Removes metadata from supported file formats.
- Helps reduce personal and identifying information embedded in files.
- Processes files locally on the device.
- Allows users to choose where to save the processed files.

### Secure File Deletion

- Performs an overwrite procedure before deleting selected files.
- Tracks overwrite and deletion operation status.
- Generates a unique Report ID for each operation.
- Maintains a local operation history.
- Generates detailed PDF reports that users can export.

### SHA-256 Hash Verification

- Calculates SHA-256 hashes of files.
- Allows users to verify file integrity using hash values.
- Useful for checking whether files have changed or been corrupted.

### Integrated Hexadecimal Editor

- Includes BinEd Hex Editor for Android, developed by the ExBin Project.
- Allows users to inspect binary file contents directly within the application.
- Supports editing and saving binary files.
- Provides a way to examine and modify file data at the hexadecimal level.

## Cryptography

S-File-Encryptor supports two file formats.

The complete binary layout of both formats is formally specified in [FORMAT SPECIFICATION](FORMAT_SPECIFICATION.md).

### V2 — Current format

New files are encrypted using:

- Argon2id for password-based key derivation
- 64 MiB memory
- 3 iterations
- 1 lane
- 256-bit derived key
- AES-256-GCM for encryption and authentication
- 12-byte random nonce
- 16-byte authentication tag
- The 48-byte file header is authenticated as GCM additional authenticated data (AAD)

The V2 file format is:

`48-byte header || AES-256-GCM ciphertext || 16-byte authentication tag`

The V2 header contains the file format version, KDF and cipher identifiers, Argon2id parameters, a 16-byte random salt, and a 12-byte random nonce.

During decryption, the GCM authentication tag is verified before the decrypted plaintext is committed to the final destination.

Large files are processed using streaming I/O to avoid loading the entire file into memory.

### V1 — Legacy format

Older files created by previous versions of S-File-Encryptor remain supported.

V1 uses:

- PBKDF2-HMAC-SHA256 with 100,000 iterations
- 512-bit derived key material
- AES-256-CBC for encryption
- HMAC-SHA256 for authentication
- 16-byte random salt
- 16-byte random IV
- 32-byte HMAC

The V1 file format is:

`16-byte salt || 16-byte IV || AES-256-CBC ciphertext || 32-byte HMAC-SHA256`

V1 files are decrypted for compatibility, but new files are always created using the V2 format.

## Decrypting Without the App

S-File-Encryptor uses a documented, open file format — not a proprietary one.

Your files are never locked in: you can decrypt any `.aes` file on any platform without installing the application.

- **[FORMAT_SPECIFICATION.md](FORMAT_SPECIFICATION.md)** — the complete binary specification of both file formats, enabling anyone to audit the format or build interoperable tools.
- **[sfe_decrypt.py](sfe_decrypt.py)** — a standalone, cross-platform Python decryptor supporting both formats. It streams large files and only releases the decrypted output after authentication succeeds.

Requirements: Python 3.9+ with two dependencies:

```bash
pip install cryptography argon2-cffi
```

Usage:

```bash
python sfe_decrypt.py backup.aes
```

The decrypted file is written alongside the input. A wrong password or a tampered file produces an authentication error, and no output file is created.

## Privacy

The application does not require network access and has no Google Play Services, analytics, advertising, or crash-reporting SDKs.

File and folder access is handled through Android's **Storage Access Framework (SAF)**. The application only accesses files and folders explicitly selected by the user.

### Secure-Delete Report Contents

Every secure-delete operation generates a local report so the user can audit what happened.

The report contains:

- A randomly generated Report ID (UUID, not derived from file content)
- A redacted file name (extension only — the base name is never recorded)
- File size
- Operation timestamps and duration
- Overwrite method and cycle count
- Number of bytes overwritten
- Per-stage statuses (overwrite, delete, overall), including any error message
- Non-identifying device/app information (manufacturer, model, Android version/API level, build ID, and app version)

The report does **not** contain file contents or content hashes.

It is stored exclusively in the app's private internal storage (`erasure_report.jsonl`), is never transmitted anywhere, and only leaves the device if the user explicitly exports it (text or single-operation PDF) via the system file picker.

The entire history can be erased at any time from within the app.

## Security Audit Report

See the [S-File-Encryptor Security Audit Report](S_FILE_ENCRYPTOR_SECURITY_AUDIT.md).

## Building

The project uses the Android Gradle Plugin and the Gradle wrapper included in this repository.

To build the application from the command line:

```bash
./gradlew assembleRelease
```

## Support the Project

If you find S-File-Encryptor useful and would like to support its continued development, you can make a voluntary donation through the following platforms:

- ☕ [Buy Me a Coffee](https://buymeacoffee.com/lukess)
- 💖 [GitHub Sponsors](https://github.com/sponsors/LukeSsnake)

Your support helps maintain and improve the project. Thank you!

## Third-Party Software

S-File-Encryptor incorporates modified source code from **BinEd Hex Editor for Android 0.2.11**, developed by the ExBin Project.

Copyright © ExBin Project.

The incorporated BinEd source code is licensed under the Apache License, Version 2.0.

- [BinEd Attribution](THIRD_PARTY_NOTICES/BINED-ATTRIBUTION.txt)
- [Apache License 2.0](THIRD_PARTY_NOTICES/BINED-LICENSE.txt)
- [ExBin Project](https://exbin.org)

## License

The original S-File-Encryptor code is licensed under the MIT License. See [LICENSE](LICENSE).

Third-party components are distributed under their respective licenses.
