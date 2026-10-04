# S File Encryptor

S File Encryptor is an Android application for encrypting and decrypting files locally with a password.

## Cryptography

The application uses:

* PBKDF2-HMAC-SHA256 with 100,000 iterations and a 512-bit derived key
* AES-256-CBC for encryption
* HMAC-SHA256 for authentication

The file format is:

`16-byte salt || 16-byte IV || AES-256-CBC ciphertext || 32-byte HMAC-SHA256`

During decryption, the authentication code is verified before the decrypted result is committed to the destination.

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
