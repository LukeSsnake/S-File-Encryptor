# S File Encryptor

S File Encryptor is an Android application for encrypting and decrypting files locally with a password.

## Cryptography

S File Encryptor supports two file formats.

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

## Privacy

The application does not require network access and has no Google Play Services, analytics, advertising, or crash-reporting SDKs.

File and folder access is handled through Android's **Storage Access Framework (SAF)**. The application only accesses files and folders explicitly selected by the user.

## Building

The project uses the Android Gradle Plugin and the Gradle wrapper included in this repository.

To build the application from the command line:

    ./gradlew assembleRelease

## Source Status

This repository contains a reconstructed and maintained implementation of S File Encryptor. It is not claimed to be byte-for-byte identical to the original private development project.

## License

MIT. See [LICENSE](LICENSE).
