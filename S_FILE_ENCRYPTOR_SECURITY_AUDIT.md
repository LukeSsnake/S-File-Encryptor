# S File Encryptor — Security Audit Report

**Application:** S File Encryptor  
**Version:** 2.0.8  
**Version Code:** 16  
**Platform:** Android  
**Audit type:** Static security and architecture review  
**Scope:** Application source code and security-sensitive components

---

## Executive Summary

A static security audit was performed on **S File Encryptor 2.0.8**, focusing on cryptography, password handling, biometric authentication, temporary files, Android Storage Access Framework (SAF), ZIP processing, metadata removal, secure deletion, manifest permissions, and general application hardening.

### Overall assessment

**Security rating: 8.7 / 10**

The application has a strong security foundation. The V2 encryption format uses modern primitives and a sound authenticated-encryption design based on **Argon2id + AES-256-GCM**. Password handling, biometric storage, ZIP validation, XML processing, and the use of private temporary storage were also positively assessed.

No critical vulnerability was identified during this static review that would allow an attacker to directly recover a V2 encryption key, bypass authentication, or decrypt files without the password.

The main remaining improvements concern temporary-file lifecycle management, adversarial testing of metadata processing, and additional testing around interruptions, crashes, and storage failures.

---

# 1. Audit Scope

The review covered the following areas:

- `CryptoEngine`
- V1 encryption/decryption compatibility
- V2 encryption/decryption
- Argon2id key derivation
- AES-256-GCM
- PBKDF2/HMAC/AES-CBC legacy format
- Password handling and memory cleanup
- `BiometricPasswordStore`
- Android Keystore usage
- `MainActivity`
- Storage Access Framework integration
- Temporary-file handling
- ZIP extraction and archive validation
- Metadata removal
- OpenXML processing
- Video metadata processing
- Secure deletion
- Android manifest and permissions
- Application hardening
- Cryptographic dependencies

The review was primarily a **static source-code audit**. It should not be interpreted as a formal penetration test, formal cryptographic proof, or independent certification.

---

# 2. Overall Findings

| Area | Assessment |
|---|---|
| V2 cryptography | 🟢 Excellent |
| V1 legacy cryptography | 🟢 Good |
| Password handling | 🟢 Very good |
| Biometric storage | 🟢 Very good |
| File integrity/authentication | 🟢 Excellent |
| Temporary files | 🟡 Improvement recommended |
| SAF integration | 🟢 Good |
| ZIP security | 🟢 Very good |
| Metadata removal | 🟢 Good |
| Secure deletion | 🟡 Provider-dependent |
| Manifest/permissions | 🟢 Very good |
| General hardening | 🟢 Very good |
| Architecture/maintainability | 🟡 Improvement possible |

---

# 3. Cryptographic Architecture

## 3.1 V2 Format

The V2 format is the strongest security component of the application.

It uses:

- **Argon2id**
- **AES-256-GCM**
- 16-byte random salt
- 12-byte random nonce
- 16-byte GCM authentication tag
- authenticated header using GCM AAD
- configurable Argon2 parameters stored in the file header
- validation of Argon2 parameters before execution

The default Argon2 configuration is:

- Memory: 64 MiB
- Iterations: 3
- Parallelism: 1

The implementation also enforces reasonable minimum and maximum values for these parameters.

### Assessment

**🟢 Excellent**

The design provides confidentiality and integrity while making password guessing substantially more expensive than using a conventional fast hash.

---

# 4. Authenticated Header

The V2 header is supplied to AES-GCM as **Additional Authenticated Data (AAD)**.

Conceptually:

```text
Password
   ↓
Argon2id
   ↓
AES-256 key
   ↓
AES-256-GCM
   ├── Header → AAD
   └── File data → ciphertext + authentication tag
```

This is an important security property.

An attacker cannot freely modify parameters such as:

- version
- KDF
- cipher identifier
- Argon2 memory
- Argon2 iterations
- Argon2 parallelism
- salt
- nonce

without causing authentication failure.

### Assessment

**🟢 Excellent**

---

# 5. V2 Decryption Safety

The application does not immediately write decrypted V2 data to the final user-selected destination.

Instead, the process is approximately:

```text
Encrypted file
      ↓
V2 authentication/decryption
      ↓
Private temporary file
      ↓
GCM authentication verified
      ↓
Final SAF destination
```

This is a particularly strong design decision.

It prevents an attacker-controlled or corrupted encrypted file from producing a partially trusted plaintext output before authentication has completed.

The implementation also checks for truncation and unexpected trailing data.

### Assessment

**🟢 Excellent**

---

# 6. V1 Legacy Format

The application maintains compatibility with the previous V1 format.

V1 uses:

- PBKDF2-HMAC-SHA256
- 100,000 iterations
- 64-byte derived key material
- AES-256-CBC
- HMAC-SHA256
- separate encryption and authentication keys

The legacy design is weaker than V2 in terms of modern password-hardening, but it is reasonable for maintaining compatibility with previously generated files.

The V1 implementation also validates authentication before accepting decrypted data.

### Recommendation

The V1 format should remain available for decryption compatibility, but new files should continue to use V2.

### Assessment

**🟢 Good for a legacy format**

---

# 7. Password Handling

Password handling is implemented using mutable character/byte arrays rather than intentionally retaining the password as a Java `String` throughout cryptographic operations.

Sensitive buffers are explicitly cleared after use.

The V1 password-derived key material is also handled with explicit cleanup.

This reduces unnecessary lifetime of sensitive material in application memory.

### Important limitation

On Android/Java, it is impossible to guarantee that no internal copy of sensitive text ever exists inside the framework, UI components, or runtime.

Nevertheless, within application-controlled cryptographic code, the current approach follows good defensive practices.

### Assessment

**🟢 Very good**

---

# 8. Biometric Authentication

The application uses Android biometric authentication and Android Keystore for protecting the stored password.

The design uses:

- Android Keystore
- AES-GCM
- biometric authentication
- `BIOMETRIC_STRONG`
- invalidation handling for invalidated Keystore keys

The plaintext password is not stored directly in SharedPreferences.

The cryptographic key used to protect the stored password is maintained through Android Keystore.

### Assessment

**🟢 Very good**

No structural change is currently considered necessary.

---

# 9. Temporary Files

Temporary files are stored in the application's private cache/storage area.

This provides strong isolation from ordinary applications.

The application also uses identifiable prefixes for its temporary files, allowing cleanup.

Examples include:

```text
sfe-v1-
sfe-v2-
sfe_input_
sfe_zip_
sfe_dec_
sfe_enc_
```

## Improvement identified

The current cleanup strategy retains some temporary files for a period before removing them.

For a privacy-oriented application, abandoned temporary files containing plaintext should ideally be removed as soon as they are known to be orphaned.

A recommended improvement is:

```text
Application startup
       ↓
Identify SFE temporary files
       ↓
Remove orphaned temporary files
```

This is particularly relevant after:

- application crash
- process termination
- force stop
- device interruption
- unexpected power loss

### Priority

**Medium / High**

### Assessment

**🟡 Good implementation with an opportunity for stronger lifecycle cleanup**

---

# 10. Storage Access Framework

The application uses Android's Storage Access Framework instead of requesting broad filesystem permissions.

This is a strong privacy and security choice.

Advantages include:

- user-selected files
- user-selected output destinations
- reduced filesystem access
- no requirement for broad storage permissions
- compatibility with Android's modern storage model

The application does not rely on:

```text
MANAGE_EXTERNAL_STORAGE
```

or legacy broad storage access.

### Assessment

**🟢 Good**

---

# 11. Secure Deletion

The secure deletion implementation uses file descriptors/channels and explicit overwriting where the underlying provider allows it.

The implementation also uses synchronization/flush mechanisms such as:

```text
FileChannel.force(true)
```

and verifies the deletion operation.

This is substantially stronger than simply deleting a file.

## Important architectural limitation

Android's Storage Access Framework operates through `DocumentsProvider`.

The application cannot control the physical storage implementation behind every provider.

For example, a provider could represent:

- internal flash storage
- SD card
- USB storage
- cloud storage
- manufacturer-specific storage
- another remote provider

Therefore, the application can request overwriting, but it cannot universally guarantee physical destruction of every underlying storage block.

This is a platform/provider limitation rather than a flaw in the overwrite algorithm itself.

### Assessment

**🟡 Good, with unavoidable provider limitations**

---

# 12. ZIP Security

ZIP handling contains multiple defensive controls.

The implementation limits:

- individual entry size
- total extracted size
- number of entries
- directory depth
- path length

It also validates paths to prevent path traversal attacks such as:

```text
../file
..\file
/absolute/path
\absolute\path
```

This substantially reduces the risk of Zip Slip and archive-based resource exhaustion.

### Assessment

**🟢 Very good**

---

# 13. Metadata Removal

The metadata functionality includes format-specific handling instead of relying exclusively on a generic cleanup operation.

The project handles several media/document formats, including:

- JPEG
- PNG
- WebP
- GIF
- PDF
- DOCX
- XLSX
- PPTX
- MP4
- M4V
- 3GP

The implementation includes structural validation and resource limits.

### Assessment

**🟢 Good**

---

# 14. OpenXML Security

For formats such as:

- DOCX
- XLSX
- PPTX

the application performs ZIP/container validation and XML processing.

Defensive measures include protection against:

- path traversal
- excessive ZIP entries
- oversized content
- XML entity attacks
- external entity resolution
- malformed container structures

The XML parser configuration rejects external entity resolution.

### Assessment

**🟢 Very good**

---

# 15. Video Metadata Processing

The video metadata implementation performs structural parsing of container atoms/boxes.

Defensive controls include validation of:

- atom sizes
- offsets
- nesting/depth
- atom counts
- integer overflow conditions
- container boundaries

The implementation also verifies that media payload data remains unchanged during metadata processing.

### Assessment

**🟢 Good**

### Recommendation

The next major test phase should include a corpus of malformed and adversarial MP4/M4V/3GP files.

---

# 16. Android Manifest and Permissions

The application follows a restrictive permission model.

No unnecessary broad storage permissions were identified.

The application does not appear to require:

```text
INTERNET
MANAGE_EXTERNAL_STORAGE
WRITE_EXTERNAL_STORAGE
```

for its core operation.

This is particularly appropriate for a privacy-focused offline encryption application.

The use of:

```text
android:allowBackup="false"
```

is also appropriate for the security model.

### Assessment

**🟢 Very good**

---

# 17. Inter-Application File Sharing

The application supports receiving files through Android intents.

This functionality is necessary for integration with Android's share system.

The reviewed flow relies on Android URI permissions rather than granting arbitrary filesystem access.

No obvious path was identified that would allow another application to arbitrarily access user-selected files through the application's sharing interface.

### Assessment

**🟢 Good**

---

# 18. Dependency Review

The project uses security-relevant libraries including:

- Bouncy Castle
- AndroidX Biometric
- AndroidX ExifInterface
- PDF-related libraries

The Bouncy Castle dependency is explicitly controlled to avoid unwanted transitive version conflicts in the PDF processing dependency tree.

No obviously malicious or unnecessary dependency was identified during this review.

### Assessment

**🟢 Good**

---

# 19. Findings Not Identified

The static review did **not** identify evidence of the following common vulnerabilities:

- ❌ hardcoded encryption keys
- ❌ hardcoded salts
- ❌ fixed AES IV/nonce
- ❌ AES-ECB usage
- ❌ unauthenticated AES-GCM
- ❌ plaintext password persistence
- ❌ passwords written to logs
- ❌ broad storage permissions
- ❌ `MANAGE_EXTERNAL_STORAGE`
- ❌ insecure TLS configuration
- ❌ cleartext network configuration
- ❌ WebView-based attack surface
- ❌ arbitrary command execution
- ❌ `Runtime.exec()` usage
- ❌ `ProcessBuilder` usage
- ❌ obvious Zip Slip vulnerability
- ❌ obvious XXE vulnerability
- ❌ direct V2 plaintext output before GCM authentication
- ❌ obvious cryptographic key persistence outside Android Keystore

This is a positive result.

---

# 20. Architecture and Maintainability

The principal non-cryptographic concern is the size and responsibility of `MainActivity`.

It currently coordinates a large number of functions, including:

- UI
- SAF
- encryption
- decryption
- ZIP processing
- metadata removal
- biometric authentication
- secure deletion
- reporting
- operation state
- progress handling

This is not itself a security vulnerability.

However, increasing complexity makes future regressions more likely.

A future architectural refactoring could separate responsibilities into components such as:

```text
MainActivity
    │
    ├── FileOperationManager
    ├── CryptoEngine
    ├── MetadataService
    ├── SecureDeleteManager
    ├── BiometricPasswordStore
    ├── ZipManager
    └── ReportManager
```

This is considered a maintainability improvement rather than an urgent security requirement.

---

# 21. Recommended Improvements

## Priority 1 — Temporary-file lifecycle

Improve startup cleanup so that abandoned SFE temporary files are removed immediately when they are known to be orphaned.

**Priority:** High

---

## Priority 2 — Adversarial testing

Create a dedicated test corpus containing:

- truncated encrypted files
- modified headers
- modified authentication tags
- modified ciphertext
- invalid Argon2 parameters
- malformed ZIP files
- ZIP bombs
- Zip Slip paths
- malformed XML
- XML entity attacks
- malformed MP4 atoms
- integer overflow cases
- extremely deep containers
- extremely large metadata blocks

**Priority:** High

---

## Priority 3 — Interruption testing

Test:

- Activity recreation
- process termination
- force stop
- low storage
- device rotation during operations
- application backgrounding
- unexpected interruption
- destination already existing
- destination creation failure
- temporary-file creation failure

**Priority:** Medium / High

---

## Priority 4 — Architecture refactoring

Gradually reduce the responsibility of `MainActivity` and move independent functionality into dedicated classes.

**Priority:** Medium

---

# 22. Security Strengths

The strongest aspects of the application are:

1. **Argon2id-based password derivation**
2. **AES-256-GCM authenticated encryption**
3. **Authenticated V2 header**
4. **Private temporary files**
5. **No plaintext V2 output before authentication**
6. **Legacy V1 compatibility**
7. **Explicit memory cleanup of sensitive buffers**
8. **Android Keystore integration**
9. **Strong biometric authentication**
10. **ZIP traversal and resource limits**
11. **XML external entity protection**
12. **Restrictive Android permissions**
13. **Offline/local-first architecture**
14. **Defensive media/container parsing**

---

# 23. Final Assessment

The audit found **no critical security vulnerability** in the reviewed implementation.

The V2 cryptographic design is particularly strong for an Android file-encryption application:

```text
Password
   ↓
Argon2id
   ↓
AES-256-GCM
   ↓
Authenticated ciphertext
```

Combined with authenticated headers, temporary-file isolation, password cleanup, Android Keystore protection, and defensive archive/document processing, the application has a solid security architecture.

The remaining recommendations are primarily focused on **hardening, robustness, lifecycle cleanup, and adversarial testing**, rather than redesigning the cryptographic system.

## Final rating

**S File Encryptor 2.0.8 — 8.7 / 10**

**Overall classification: 🟢 Strong security posture**

This report represents a static source-code review and is not a substitute for a formal third-party penetration test, cryptographic audit, or security certification.
