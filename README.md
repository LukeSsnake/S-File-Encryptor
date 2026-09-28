# S File Encryptor

S File Encryptor is an Android application for encrypting and decrypting files locally with a password.

## Cryptography

The application uses:

- PBKDF2-HMAC-SHA256 with 100,000 iterations and a 512-bit derived key
- AES-256-CBC for encryption
- HMAC-SHA256 for authentication

The file format is:

`16-byte salt || 16-byte IV || AES-256-CBC ciphertext || 32-byte HMAC-SHA256`

Decryption verifies the authentication code before the decrypted result is committed to the destination.

## Privacy

The application does not require network access and has no Google Play Services, analytics, advertising, or crash-reporting SDKs.

For compatibility with Android versions that support it, the application uses `MANAGE_EXTERNAL_STORAGE` to access arbitrary files in shared storage. The legacy `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` permissions are declared only through API 28.

## Building

The project uses the Android Gradle Plugin and the Gradle wrapper included in this repository. The build can be performed with the command-line Gradle wrapper included in the repository.

```text
./gradlew assembleRelease
```

The F-Droid build is performed independently from the published source repository.

## Source status

This repository contains a reconstructed and maintained implementation of S File Encryptor. It is not claimed to be byte-for-byte identical to the original private development project.

## License

MIT. See [LICENSE](LICENSE).
