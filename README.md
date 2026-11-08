# S File Encryptor

S File Encryptor is an Android application for encrypting and decrypting files locally with a password.

[![Google Play](https://img.shields.io/badge/Google%20Play-Download-414141?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.lukestudio.fileencryptor2)

## Cryptography

S File Encryptor supports two file formats.

The complete binary layout of both
formats is formally specified in [FORMAT SPECIFICATION](FORMAT_SPECIFICATION.md).

### V2 — Current format

New files are encrypted using:

* Argon2id for password-based key derivation
* 64 MiB memory
* 3 iterations
* 1 lane
* 256-bit derived key
* AES-256-GCM for encryption and authentication
* 12-byte random nonce
* 16-byte authentication tag
* The 48-byte file header is authenticated as GCM additional authenticated data (AAD)

The V2 file format is:

`48-byte header || AES-256-GCM ciphertext || 16-byte authentication tag`

The V2 header contains the file format version, KDF and cipher identifiers, Argon2id parameters, a 16-byte random salt, and a 12-byte random nonce.

During decryption, the GCM authentication tag is verified before the decrypted plaintext is committed to the final destination.

Large files are processed using streaming I/O to avoid loading the entire file into memory.

### V1 — Legacy format

Older files created by previous versions of S File Encryptor remain supported.

V1 uses:

* PBKDF2-HMAC-SHA256 with 100,000 iterations
* 512-bit derived key material
* AES-256-CBC for encryption
* HMAC-SHA256 for authentication
* 16-byte random salt
* 16-byte random IV
* 32-byte HMAC

The V1 file format is:

`16-byte salt || 16-byte IV || AES-256-CBC ciphertext || 32-byte HMAC-SHA256`

V1 files are decrypted for compatibility but new files are always created using the V2 format.

## Decrypting Without the App

S-File Encryptor uses a documented, open file format — not a proprietary one.
Your files are never locked in: you can decrypt any `.aes` file on any platform
without installing the application.

* **[FORMAT_SPECIFICATION.md](FORMAT_SPECIFICATION.md)** — the complete binary
  specification of both file formats, enabling anyone to audit the format or
  build interoperable tools.
* **[sfe_decrypt.py](sfe_decrypt.py)** — a standalone, cross-platform Python
  decryptor supporting both formats. It streams large files and only releases
  the decrypted output after authentication succeeds.

Requirements: Python 3.9+ with two dependencies:

    pip install cryptography argon2-cffi

Usage:

    python sfe_decrypt.py backup.aes

The decrypted file is written alongside the input. A wrong password or a
tampered file produces an authentication error, and no output file is created.

## Privacy

The application does not require network access and has no Google Play Services, analytics, advertising, or crash-reporting SDKs.

File and folder access is handled through Android's **Storage Access Framework (SAF)**. The application only accesses files and folders explicitly selected by the user.

### Secure-Delete Report Contents

Every secure-delete operation generates a local report so the user can
audit what happened. The report contains: a randomly generated Report ID
(UUID, not derived from file content), a redacted file name (extension only — the base name is never recorded), the file size,
operation timestamps and duration, the overwrite method and cycle count,
the number of bytes overwritten, per-stage statuses (overwrite, delete,
overall) with any error message, and non-identifying device/app
information (manufacturer, model, Android version/API level, build ID,
app version).

The report does **not** contain file contents or content hashes. It is
stored exclusively in the app's private internal storage
(`erasure_report.jsonl`), is never transmitted anywhere, and only leaves
the device if the user explicitly exports it (text or single-operation
PDF) via the system file picker. The entire history can be erased at any
time from within the app.

## File Privacy Tools

In addition to file encryption, S File Encryptor includes tools designed to help users protect their privacy and manage sensitive files.

### Metadata Removal

SFE can remove metadata from supported file formats, helping users reduce the amount of personal and identifying information embedded in their files.

This feature is useful before sharing photos, videos, audio files, documents, and other supported content.

The application processes files locally on the device and allows the user to choose where to save the resulting file.

### Secure File Deletion

SFE includes a file deletion feature that performs an overwrite procedure before deleting the selected file.

The operation includes:

* File overwrite processing
* Deletion after the overwrite procedure
* Operation status tracking
* A unique Report ID for each operation
* A detailed PDF report containing operation and device information
* A persistent operation history

The PDF report can be exported through Android's Storage Access Framework (SAF).

These tools provide additional privacy features beyond encryption, helping users manage metadata and sensitive files directly on their Android devices.

## Building

The project uses the Android Gradle Plugin and the Gradle wrapper included in this repository.

To build the application from the command line:

    ./gradlew assembleRelease

## Security Audit Report

See [Report](https://github.com/LukeSsnake/S-File-Encryptor/blob/main/S_FILE_ENCRYPTOR_SECURITY_AUDIT.md)

## Support the Project

If you find S File Encryptor useful and would like to support its continued development, you can make a voluntary donation through the following platforms:

- ☕ [Buy Me a Coffee](https://buymeacoffee.com/lukess)
- 💖 [GitHub Sponsors](https://github.com/sponsors/LukeSsnake)

Your support helps maintain and improve the project. Thank you!

## License

MIT. See [LICENSE](LICENSE).
