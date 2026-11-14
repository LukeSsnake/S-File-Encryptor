# S-File Encryptor — Security Audit Report

**Project:** S-File Encryptor  
**Package:** `com.lukestudio.fileencryptor2`  
**Audit scope:** Application functionality, cryptography, file handling, temporary files, password handling, biometric authentication, metadata removal, secure deletion, SHA-256 verification, and integrated BinEd hexadecimal editor.

## 1. Executive Summary

A security audit was performed on the current S-File Encryptor implementation, including its encryption/decryption engine and the application's additional file-management and editing features.

The current implementation was assessed as **strong overall**, with an approximate security rating of:

> **9.1 / 10**

No confirmed **critical** or **high-severity** vulnerabilities were identified during the audit.

The strongest parts of the application are:

- Argon2id + AES-256-GCM for the current V2 encryption format.
- Authenticated encryption with the V2 header protected as GCM AAD.
- Authentication of decrypted data before exposing the plaintext through the final output.
- Backward compatibility with the legacy V1 format.
- Password handling using `char[]` rather than unnecessarily retaining plaintext passwords as `String`.
- Android Keystore-backed biometric password protection.
- SAF-based file access.
- ZIP extraction limits and path validation.
- Detection of corrupted, truncated, and modified encrypted files.
- Temporary-file cleanup.
- `FLAG_SECURE` for protection against screenshots and screen capture.
- Additional integrity and file-security features such as SHA-256 verification and secure deletion.

Some areas remain dependent on Android `DocumentProvider` behavior or on the exact implementation of individual features. These are primarily **medium- or low-risk hardening opportunities**, rather than confirmed vulnerabilities.

---

# 2. Audit Methodology

The audit focused on the application's actual functionality rather than only reviewing individual classes.

The following areas were examined:

1. File encryption.
2. File decryption.
3. Folder encryption.
4. Folder decryption.
5. Password handling.
6. Biometric authentication.
7. Temporary files.
8. SAF file access.
9. ZIP processing.
10. Metadata removal.
11. Secure deletion.
12. SHA-256 hashing.
13. Integrated BinEd hexadecimal editor.
14. Error handling.
15. Android lifecycle behavior.
16. Screen protection.
17. File corruption and tampering resistance.

The assessment also considered practical attack scenarios such as:

- Wrong passwords.
- Modified encrypted files.
- Truncated encrypted files.
- Interrupted operations.
- Invalid file sizes.
- Malicious ZIP archives.
- Temporary-file exposure.
- Failed file operations.
- Invalid write positions in the hexadecimal editor.

---

# 3. Overall Security Assessment

| Area | Assessment | Status |
|---|---|---|
| V2 cryptography | Excellent | Strong |
| V1 legacy compatibility | Good | Secure for legacy compatibility |
| Password handling | Strong | Good |
| Biometric password protection | Strong | Good |
| File decryption integrity | Excellent | Strong |
| SAF integration | Strong | Good |
| ZIP processing | Strong | Good |
| Temporary files | Good | Hardening possible |
| Metadata removal | Good | Requires format-specific testing |
| Secure deletion | Good | Provider/storage dependent |
| SHA-256 verification | Strong | Good |
| BinEd integration | Good | Hardened |
| Error handling | Good | Improved |
| Screen protection | Strong | Good |
| Lifecycle management | Good | Further review recommended |

---

# 4. Cryptography

## 4.1 V2 Encryption Format

The V2 format is the preferred encryption format used by the application.

It uses:

- **Argon2id** for password-based key derivation.
- **AES-256-GCM** for authenticated encryption.
- A 16-byte salt.
- A 12-byte GCM nonce.
- A 16-byte authentication tag.
- A 48-byte header.
- The header is authenticated as **GCM Additional Authenticated Data (AAD)**.

This is a modern and appropriate construction for password-based file encryption.

### Assessment

**Strong / Excellent**

Using Argon2id makes brute-force password attacks significantly more expensive than using a conventional fast hash.

AES-256-GCM provides both:

- Confidentiality.
- Integrity/authenticity.

The use of the header as AAD is particularly important because parameters such as KDF configuration and other header fields cannot be modified without causing authentication failure.

---

# 5. V2 Decryption Integrity

One of the strongest security properties of the current implementation is the handling of decrypted data.

The application does not immediately expose authenticated plaintext through the final SAF destination.

Instead, V2 decryption uses a private temporary file and only copies the plaintext to the selected output after authentication has successfully completed.

The implementation also rejects unexpected trailing data.

This significantly reduces the risk of producing apparently valid output from a corrupted or modified encrypted file.

### Tested behavior

The following tests were successfully performed:

- Correct password → successful decryption.
- Incorrect password → password error.
- Single-byte modification → corruption/tampering detected.
- Truncation → corruption/tampering detected.
- Existing output file behavior → original file remained intact when a new SAF output document was created.

### Assessment

**Excellent**

---

# 6. V1 Legacy Format

The application continues to support the older V1 format for compatibility.

V1 uses:

- AES-256-CBC.
- HMAC-SHA256.
- PBKDF2-HMAC-SHA256.
- 100,000 PBKDF2 iterations.
- 16-byte salt.
- 16-byte IV.
- 32-byte HMAC.
- 64-byte derived key material.

The derived key is divided into:

- 32-byte AES key.
- 32-byte HMAC key.

### Assessment

**Good for legacy compatibility**

V1 is not the preferred format for newly encrypted files, but its use of separate encryption and authentication keys with HMAC provides authenticated integrity protection.

The application correctly maintains compatibility rather than unnecessarily breaking access to existing encrypted files.

V2 should remain the preferred format for new encryption.

---

# 7. Password Handling

The application uses `char[]` for password handling in the relevant encryption/decryption workflow instead of unnecessarily retaining plaintext passwords as immutable Java `String` objects.

Password arrays are explicitly cleared after use where appropriate.

The application also enforces a minimum password length for password creation/change operations while allowing shorter passwords when decrypting legacy files.

This distinction is important because enforcing a new password policy during legacy decryption could prevent users from recovering older files.

### Assessment

**Strong**

The decision to retain password visibility in the UI for user convenience is not itself considered a security vulnerability. The important issue is avoiding unnecessary long-lived plaintext copies in application memory.

---

# 8. Biometric Authentication

The application supports biometric authentication using Android's strong biometric authentication mechanisms.

The biometric workflow uses:

- `BiometricPrompt`.
- `BIOMETRIC_STRONG`.
- Android Keystore-backed cryptographic operations.
- AES-GCM for protected password storage.
- Separate encryption/decryption cipher creation.

The plaintext password is not simply stored as a normal application preference.

### Assessment

**Strong**

The biometric feature provides a substantially better protection model than storing the user's encryption password directly in application preferences.

The manual password entry fallback is also preserved.

---

# 9. Android Screen Protection

The application enables:

```java
WindowManager.LayoutParams.FLAG_SECURE
```

This is an important protection for an application that handles sensitive passwords and decrypted files.

It helps prevent screenshots and screen capture by supported Android mechanisms.

### Assessment

**Strong**

---

# 10. SAF File Access

The application uses Android's Storage Access Framework for user-selected files and output destinations.

This is preferable to requesting broad filesystem permissions unnecessarily.

The application therefore operates within Android's document access model rather than requiring unrestricted storage access.

### Assessment

**Strong**

The main limitation is that behavior can depend on the selected Android `DocumentProvider`. This is an Android platform consideration rather than a confirmed application vulnerability.

---

# 11. Folder Encryption and Decryption

Folder operations use ZIP processing with explicit limits.

The application imposes restrictions including:

- Maximum individual entry size.
- Maximum total extracted size.
- Maximum number of entries.
- Maximum directory depth.
- Maximum path length.

These restrictions significantly reduce the risk of resource exhaustion through malicious archives.

### Assessment

**Strong**

The implemented limits provide important protection against ZIP bombs and other archive-based denial-of-service scenarios.

---

# 12. Temporary Files

Temporary files are used during several sensitive operations, especially V2 decryption.

The application uses application-controlled temporary storage and performs cleanup of temporary files.

The implementation also uses recognizable prefixes for its temporary files, including:

- `sfe-v1-`
- `sfe-v2-`
- `sfe_input_`
- `sfe_zip_`
- `sfe_dec_`
- `sfe_enc_`
- `sfe_meta_in_`
- `sfe_meta_out_`

The application performs cleanup of old temporary files.

### Assessment

**Good**

Temporary-file handling is substantially better than leaving decrypted data in arbitrary external locations.

Further hardening could include continued lifecycle testing under process termination and unusual storage-provider conditions.

---

# 13. Metadata Removal

The application includes a dedicated metadata-removal feature.

The selected document is processed and the cleaned result is subsequently passed through the SAF save flow.

This is a useful privacy feature because metadata can contain information unrelated to the visible content of a file.

### Assessment

**Good, but format dependent**

Metadata handling is inherently dependent on the file format.

Different formats store metadata differently, so the feature should be tested independently against formats such as:

- JPEG.
- PNG.
- WebP.
- MP4.
- MP3.
- PDF.
- DOCX.
- XLSX.
- PPTX.

A successful metadata-removal implementation for one format does not automatically guarantee complete metadata removal for every other format.

---

# 14. Secure Deletion

The application includes a secure-deletion feature that attempts to overwrite file contents before deletion.

The feature is useful for files stored on storage media where overwrite operations are meaningful.

However, the actual behavior of physical storage is ultimately controlled by:

- The filesystem.
- Android's storage layer.
- The selected `DocumentProvider`.
- The underlying storage hardware.

Therefore, the application should treat the operation as a secure deletion procedure rather than assuming that every physical storage block has necessarily been destroyed.

### Assessment

**Good / implementation dependent**

The feature provides useful protection at the application/file-system level, while its exact guarantees depend on the storage medium and provider.

---

# 15. SHA-256 Hash Verification

The application includes SHA-256 hashing.

This provides a standard mechanism for verifying file integrity.

SHA-256 is appropriate for:

- Comparing files.
- Verifying downloads.
- Detecting accidental modifications.
- Confirming that two files have identical content.

### Assessment

**Strong**

The feature should not be interpreted as a replacement for authenticated encryption, but it is a useful standalone integrity-verification tool.

---

# 16. Integrated BinEd Hexadecimal Editor

The application integrates the BinEd hexadecimal editor as an additional file-editing feature.

Because the editor can directly modify file contents, it was treated as a security-sensitive component.

Particular attention was given to:

- File opening.
- File saving.
- Random-access writes.
- Write-position validation.
- Integer overflow.
- File length handling.
- Resource cleanup.
- Error reporting.

---

# 17. BinEd `ContentDataSource` Hardening

The `ContentDataSource` implementation was hardened to validate write operations.

The current implementation rejects:

- Negative positions.
- Invalid lengths.
- Invalid offsets.
- Integer/long overflow when calculating the end position.
- Invalid write parameters.

For example, the implementation checks:

```java
if (position > Long.MAX_VALUE - length) {
    throw new IOException("Write position overflow");
}
```

This prevents arithmetic overflow from producing an invalid write location.

The implementation also uses a `writeFully()` helper to ensure that the requested data is completely written.

### Assessment

**Strong**

These checks significantly improve the robustness of random-access file editing.

---

# 18. BinEd File Persistence Testing

The hexadecimal editor was tested by modifying bytes at several positions, including:

- Position 0.
- Position 1.
- Position 100.

The file was saved, closed, reopened, and the modified bytes remained correct.

This confirmed that the current random-access write implementation persists changes correctly.

### Assessment

**Passed**

---

# 19. BinEd Error Handling

The original BinEd integration logged some file-opening and saving errors without presenting them clearly to the user.

This was improved so that `IOException` failures now produce a visible Android `Toast`.

For example:

```java
Toast.makeText(
        codeArea.getContext(),
        "Erro ao abrir arquivo: " + ex.getMessage(),
        Toast.LENGTH_LONG
).show();
```

and similarly for saving errors.

This does not change the underlying file operation, but it prevents failures from being silently hidden from the user.

### Assessment

**Improved / Good**

---

# 20. BinEd Save Behavior

The editor was also tested with an existing file.

When saving, the SAF workflow created a new document such as:

```text
File.pdf (1)
```

while the original:

```text
File.pdf
```

remained intact.

This behavior avoids accidental destruction of the original document during normal use.

### Assessment

**Good**

No additional transactional-save architecture is currently considered necessary based on the observed behavior.

---

# 21. Lifecycle and Resource Management

The application performs cleanup of important resources during lifecycle transitions.

The main activity:

- Shuts down its executor.
- Clears pending password material.
- Clears password-related UI state.
- Performs temporary-file cleanup.
- Uses controlled asynchronous execution for long-running operations.

The BinEd integration also contains explicit cleanup for file resources and editor-related objects.

### Assessment

**Good**

The lifecycle handling is generally appropriate.

A further dedicated audit of `HexEditorActivity` and its complete lifecycle remains a useful hardening task, especially around:

- `onDestroy()`.
- `ContentDataSource.close()`.
- `DeltaDocument`.
- Undo/redo resources.
- Search operations.
- Selection listeners.
- Clipboard handling.
- Asynchronous operations.

No confirmed high-severity lifecycle vulnerability was identified.

---

# 22. Error Handling

The application has explicit error handling for important failure cases, including:

- Incorrect passwords.
- Corrupted files.
- Truncated encrypted files.
- Invalid encrypted-file structures.
- Missing temporary directories.
- Invalid write operations.
- File I/O errors.
- ZIP extraction failures.

This is particularly important for cryptographic operations because a failed authentication must not be treated as successful decryption.

### Assessment

**Good**

The distinction between incorrect passwords and corrupted files also improves usability without weakening the cryptographic verification process.

---

# 23. Tampering Resistance

The application was tested against modifications to encrypted files.

The observed behavior was:

| Test | Result |
|---|---|
| Correct password | Successful |
| Wrong password | Rejected |
| V1 byte modification | Detected |
| V2 byte modification | Detected |
| V1 truncation | Detected |
| V2 truncation | Detected |
| V2 trailing data | Rejected |

This demonstrates that the authenticated encryption/integrity mechanisms are functioning correctly in practical tests.

### Assessment

**Excellent**

---

# 24. Interrupted Operations

Interrupted operations were also tested.

In one test involving an interrupted operation, the residual output file was observed with a size of:

```text
0 bytes
```

When an existing SAF destination was involved, the original file remained intact while a separate output document was created.

This is preferable to blindly truncating an existing user file before successful completion.

### Assessment

**Good**

---

# 25. Security Strengths

The following are considered the strongest security properties of the current application:

### Cryptography

- Argon2id.
- AES-256-GCM.
- Authenticated headers.
- Random salt and nonce.
- Legacy V1 support with HMAC authentication.
- No reliance on encryption alone for integrity.

### File handling

- SAF integration.
- Private temporary files.
- Delayed exposure of authenticated plaintext.
- Temporary-file cleanup.
- File-size limits.

### Password protection

- `char[]` password handling.
- Explicit memory clearing where applicable.
- Strong biometric authentication.
- Android Keystore.
- No unnecessary plaintext password persistence.

### Archive security

- Entry-size limits.
- Total extraction limits.
- Entry-count limits.
- Path-depth limits.
- Path-length limits.

### Application hardening

- `FLAG_SECURE`.
- Controlled background execution.
- Explicit resource cleanup.
- Visible I/O error reporting.

### Additional security features

- Metadata removal.
- Secure deletion.
- SHA-256 verification.
- Hexadecimal editing with validated random-access writes.

---

# 26. Remaining Hardening Opportunities

No critical or high-severity issue was identified, but the following areas remain worth reviewing.

## Medium/low-priority

### 26.1 Hex Editor Lifecycle

A dedicated lifecycle review of the complete `HexEditorActivity` would be useful.

Particular attention should be given to:

- Asynchronous searches.
- Search cancellation.
- Listener removal.
- `ContentDataSource` closure.
- Delta document disposal.
- Undo/redo resources.
- Clipboard behavior.

### 26.2 Metadata Format Coverage

Metadata removal should continue to be tested against different file formats.

Different containers have different metadata mechanisms, so format-specific validation is important.

### 26.3 Secure Deletion Across Providers

Secure deletion should be tested with:

- Internal storage.
- SD cards.
- Different Android `DocumentProvider` implementations.
- Read-only documents.
- Large files.
- Interrupted deletion operations.

### 26.4 Temporary-File Lifecycle

Additional testing under:

- Activity destruction.
- Process termination.
- Device rotation.
- Low-storage conditions.
- Application force-stop.

would provide additional confidence in temporary-data cleanup.

---

# 27. Risk Classification

The current audit did not identify any confirmed issue that should be classified as:

- Critical.
- High severity.

The remaining issues are primarily:

**Medium:**
- Provider-dependent file operations.
- Metadata completeness depending on file format.
- Secure deletion guarantees depending on storage technology.

**Low:**
- Additional lifecycle hardening.
- Additional cleanup testing.
- Additional edge-case validation.

---

# 28. Final Assessment

The current S-File Encryptor implementation provides a strong security architecture for an Android file-encryption application.

The most important security decisions are sound:

1. New encryption uses Argon2id and AES-256-GCM.
2. The encrypted header is authenticated.
3. Decryption does not expose plaintext until authentication succeeds.
4. Legacy V1 files remain supported.
5. Password material is handled using mutable arrays and cleared where appropriate.
6. Biometric password protection uses Android Keystore.
7. SAF is used for user-selected files.
8. ZIP processing has explicit resource limits.
9. Temporary files are controlled and cleaned.
10. Screen capture protection is enabled.
11. File tampering and truncation are detected.
12. The hexadecimal editor validates random-access writes.
13. File-operation errors are surfaced to the user.
14. Additional privacy/integrity tools are available through metadata removal, secure deletion, and SHA-256 verification.

Based on the current implementation and testing, the application can be considered **strong from a practical application-security perspective**, with the remaining work primarily consisting of additional hardening and broader compatibility testing rather than remediation of known critical vulnerabilities.

**Overall assessment: 9.1 / 10**

---

## Audit Status

**Status:** Passed — no confirmed critical or high-severity vulnerabilities identified.

**Recommended next step:** Perform a dedicated lifecycle/resource audit of the integrated BinEd `HexEditorActivity`, followed by format-specific testing of the metadata-removal feature and provider-specific testing of secure deletion.
