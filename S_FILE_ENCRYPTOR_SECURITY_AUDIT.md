# S-File-Encryptor 3.1.9 — Security Audit Report

## Executive Summary

A static security audit was performed on **S-File-Encryptor version 3.1.9**, based on the complete source archive `S-File-Encryptor-main-3.1.9.zip`.

The audit focused on the application's security-critical components, including:

- Cryptographic implementation
- Argon2id key derivation
- AES-256-GCM encryption
- Legacy V1 compatibility
- Password handling
- Biometric authentication
- Android Keystore
- Temporary files
- Storage Access Framework (SAF)
- ZIP archive processing
- XML/OpenXML processing
- MP4 metadata processing
- Secure deletion
- Android manifest and permissions
- Application hardening
- Third-party dependencies
- Error handling and interruption scenarios

### Overall Security Assessment

**Security Rating: 9.1 / 10**

No critical vulnerability was identified during this static audit that would allow an attacker to:

- Recover a V2 encryption key without the password
- Bypass AES-GCM authentication
- Decrypt a modified V2 file
- Bypass biometric authentication through an obvious application-level flaw
- Perform an obvious Zip Slip attack
- Perform an obvious XXE attack
- Execute arbitrary system commands
- Access unrestricted external storage through dangerous permissions
- Recover passwords from application logs
- Obtain plaintext from a tampered encrypted file before authentication

The **V2 cryptographic architecture is the strongest component of the application**. The main remaining security improvements concern temporary-file lifecycle management, interruption/crash testing, adversarial parser testing, dependency auditing, and architectural separation of responsibilities.

---

# 1. Audit Scope

The audit covered the following security-sensitive components and functionality:

- `CryptoEngine`
- V1 encryption/decryption
- V2 encryption/decryption
- Argon2id
- AES-256-GCM
- PBKDF2-HMAC-SHA256
- HMAC-SHA256
- Password memory handling
- `BiometricPasswordStore`
- Android Keystore
- `MainActivity`
- Storage Access Framework
- Temporary-file handling
- ZIP extraction
- ZIP traversal protections
- ZIP resource limits
- OpenXML processing
- XML entity handling
- MP4 metadata processing
- Secure deletion
- Android Manifest
- Exported components
- Application hardening
- Third-party cryptographic dependencies

This was a **static source-code security review**.

It was not:

- A formal penetration test
- A cryptographic proof
- A hardware security evaluation
- A forensic examination
- A formal certification
- An independent code-signing or supply-chain audit

---

# 2. Overall Findings

| Area | Assessment |
|---|---|
| V2 cryptography | 🟢 Excellent |
| Argon2id | 🟢 Very good |
| AES-256-GCM | 🟢 Excellent |
| Authenticated header | 🟢 Excellent |
| File integrity | 🟢 Excellent |
| V1 legacy cryptography | 🟢 Good |
| Password handling | 🟢 Very good |
| Biometric storage | 🟢 Excellent |
| Android Keystore | 🟢 Excellent |
| Temporary files | 🟡 Improvement recommended |
| SAF integration | 🟢 Excellent |
| ZIP security | 🟢 Very good |
| XML/XXE protection | 🟢 Very good |
| MP4 parsing | 🟢 Good |
| Secure deletion | 🟡 Provider-dependent |
| Manifest/security permissions | 🟢 Excellent |
| Application hardening | 🟢 Very good |
| Architecture | 🟡 Improvement recommended |
| Dependency security | 🟡 Additional verification recommended |

---

# 3. V2 Cryptographic Architecture

## 3.1 Overview

The V2 encryption format is the strongest security component of S-File-Encryptor.

It uses:

- Argon2id
- AES-256-GCM
- 16-byte random salt
- 12-byte random nonce
- 16-byte authentication tag
- 32-byte encryption key
- Authenticated header
- Configurable Argon2 parameters
- Defensive parameter validation

The default Argon2 configuration is:

```text
Memory:      64 MiB
Iterations:  3
Parallelism: 1
```

The implementation also applies defensive limits to Argon2 parameters before memory-intensive processing begins.

### Assessment

**🟢 Excellent**

The combination of a memory-hard password KDF and authenticated encryption provides strong protection against offline password guessing and ciphertext modification.

---

# 4. Authenticated V2 Header

The V2 header contains information including:

- Magic value
- Version
- KDF identifier
- Cipher identifier
- Reserved byte
- Argon2 memory
- Argon2 iterations
- Argon2 parallelism
- Salt
- Nonce

The complete header is supplied as **AES-GCM Additional Authenticated Data (AAD)**.

Conceptually:

```text
Header
   |
   +--> AAD
   |
   +--> authenticated by GCM
```

This is an important security property.

An attacker cannot silently modify parameters such as:

```text
Argon2 memory
Argon2 iterations
Argon2 parallelism
cipher identifier
version
salt
nonce
```

without causing authentication failure.

### Assessment

**🟢 Excellent**

---

# 5. V2 Decryption and Plaintext Exposure

One of the strongest design decisions is that decrypted plaintext is not immediately written to the user's final destination.

The implementation uses a private temporary file during V2 decryption.

The general flow is:

```text
Encrypted file
      |
      v
Validate header
      |
      v
Derive Argon2id key
      |
      v
AES-256-GCM verification/decryption
      |
      v
Private temporary file
      |
      v
GCM authentication
      |
      v
Final output
```

This prevents a corrupted or maliciously modified encrypted file from producing an apparently valid plaintext file before authentication has completed.

### Security benefit

If the ciphertext or authentication tag is modified:

```text
GCM authentication fails
        |
        v
temporary plaintext is discarded
        |
        v
final destination is not accepted as valid output
```

### Assessment

**🟢 Excellent**

---

# 6. Protection Against File Replacement or Modification

The application performs additional verification between the file validation and decryption stages.

The implementation uses separate input streams and validates the relevant header/data before relying on the second processing pass.

This provides protection against a class of race conditions in which the underlying document changes between validation and processing.

This is particularly useful when processing files through Android's Storage Access Framework, where the underlying provider may not behave exactly like a conventional local filesystem.

### Assessment

**🟢 Excellent**

---

# 7. Legacy V1 Format

S-File-Encryptor maintains compatibility with the legacy V1 format.

The V1 design uses:

```text
PBKDF2-HMAC-SHA256
        |
        v
64-byte derived material
        |
        +--> 32-byte AES key
        |
        +--> 32-byte HMAC key

AES-256-CBC
        +
HMAC-SHA256
```

The V1 format uses:

- 16-byte salt
- 16-byte IV
- 100,000 PBKDF2 iterations
- 256-bit AES key
- 256-bit HMAC key
- Encrypt-then-MAC design

### Security assessment

The V1 implementation is reasonable for a legacy format.

However, PBKDF2 with 100,000 iterations is substantially less resistant to modern offline password cracking than Argon2id.

This is a **legacy-format limitation**, not a new vulnerability in version 3.1.9.

### Recommendation

Continue writing new files exclusively in V2.

Maintain V1 support only for compatibility.

### Assessment

**🟢 Good**

---

# 8. Password Handling

Password handling is carefully implemented.

The application uses `char[]` rather than intentionally maintaining the password as a persistent Java `String` throughout the cryptographic pipeline.

The general conversion is:

```text
char[]
   |
   v
UTF-8 byte[]
   |
   v
Argon2id
```

Sensitive buffers are explicitly cleared after use.

The application also clears password character arrays during operation cleanup.

### Security consideration

Java and Android do not provide absolute control over every temporary memory representation created internally by the runtime.

Therefore, memory wiping cannot guarantee that every historical copy of a password has disappeared from RAM.

However, the implementation correctly minimizes deliberate persistent copies and explicitly clears sensitive buffers.

### Assessment

**🟢 Very Good**

---

# 9. Biometric Authentication

The biometric password storage implementation uses Android Keystore.

The architecture is:

```text
User password
      |
      v
AES-256-GCM
      |
      v
Encrypted password
      |
      v
SharedPreferences

Encryption key
      |
      v
Android Keystore
```

The Keystore key is configured with:

- AES
- 256-bit key
- GCM
- No padding
- User authentication required
- Strong biometric authentication
- Randomized encryption
- Biometric enrollment invalidation where supported

The plaintext password is not stored directly in SharedPreferences.

### Assessment

**🟢 Excellent**

---

# 10. Biometric Security Model

The biometric system protects the locally stored password.

It should therefore be understood as:

```text
Biometric authentication
        |
        v
Access to locally stored password
        |
        v
Password used for file decryption
```

It does not replace the cryptographic security of the encrypted file itself.

The actual file remains protected by the password-derived Argon2id/AES-256-GCM key.

This is an appropriate security model for a local file-encryption application.

---

# 11. Temporary Files

Temporary-file handling is one of the main areas where additional hardening is recommended.

The application uses private application storage for sensitive temporary files, which is an important security property.

However, temporary files can potentially survive:

- Process termination
- Application crashes
- Force-stop
- Device shutdown
- Storage failures
- Unexpected interruptions

The application performs cleanup of old temporary files during startup.

This substantially reduces long-term exposure, but it does not provide immediate cleanup after every possible abnormal termination.

### Finding: SFE-001

**Title:** Temporary files may survive abnormal termination

**Severity:** Low/Medium

**Impact:**

Sensitive plaintext or intermediate data may remain in the application's private storage until a later cleanup operation.

**Recommendation:**

Improve the temporary-file lifecycle with explicit operation tracking and more aggressive orphan detection.

Possible approach:

```text
Operation starts
      |
      v
Create uniquely identified temporary file
      |
      v
Register active operation
      |
      v
Perform operation
      |
      +---- success ----> delete temporary state
      |
      +---- failure -----> delete temporary state
      |
      +---- crash -------> detect orphan during next startup
```

### Assessment

**🟡 Improvement recommended**

---

# 12. `deleteOnExit()` Consideration

The implementation includes `deleteOnExit()` as a fallback for temporary-file cleanup.

This should not be treated as the primary security guarantee on Android.

The preferred mechanism remains explicit deletion:

```text
File.delete()
```

followed by appropriate orphan cleanup during future application startup.

### Finding: SFE-002

**Severity:** Low

**Recommendation:**

Treat `deleteOnExit()` only as a secondary fallback and do not rely on it as a guaranteed Android secure-deletion mechanism.

---

# 13. Storage Access Framework

The application makes extensive use of Android's Storage Access Framework.

This is a strong architectural choice.

The application does not rely on broad external-storage permissions such as:

```text
READ_EXTERNAL_STORAGE
WRITE_EXTERNAL_STORAGE
MANAGE_EXTERNAL_STORAGE
```

for normal operation.

Instead, access is granted through user-selected URIs.

### Security benefits

This significantly reduces:

- unnecessary filesystem access
- attack surface
- accidental access to unrelated user files
- permission abuse risk

### Assessment

**🟢 Excellent**

---

# 14. ZIP Security

ZIP processing is one of the better-hardened areas of the application.

Defensive limits include restrictions on:

- Maximum number of entries
- Maximum entry size
- Maximum total extracted size
- Maximum path length
- Maximum nesting depth
- Path traversal
- Absolute paths
- Windows-style paths
- Duplicate/conflicting entries
- Invalid path components

This significantly reduces exposure to common archive attacks.

### Protected attack classes

The implementation addresses risks associated with:

- Zip Slip
- Path traversal
- Archive bombs
- Resource exhaustion
- Excessive nesting
- Malicious filenames
- File/directory namespace conflicts

### Assessment

**🟢 Very Good**

---

# 15. OpenXML and XML Security

XML processing contains several important security controls.

The parser attempts to disable:

- DOCTYPE declarations
- External general entities
- External parameter entities
- External DTD loading
- XInclude

An explicit entity resolver is also used to reject external entity resolution.

This significantly reduces the risk of XML External Entity attacks.

XML and archive size limits provide an additional layer against resource exhaustion.

### Assessment

**🟢 Very Good**

---

# 16. MP4 Metadata Processing

The MP4 metadata cleaner performs defensive parsing of ISO-BMFF structures.

The implementation checks values including:

- Atom sizes
- Offsets
- File boundaries
- Integer calculations
- Table boundaries
- Structural consistency

The processing is performed using temporary data rather than modifying the original file directly.

### Assessment

**🟢 Good**

No obvious memory-corruption or unrestricted-offset vulnerability was identified during this static review.

---

# 17. Secure Deletion

The secure deletion implementation performs an overwrite operation through the selected document URI.

The general approach is:

```text
Document URI
      |
      v
Writable file descriptor
      |
      v
FileChannel
      |
      v
Random overwrite
      |
      v
force(true)
      |
      v
Size / operation validation
```

The implementation also checks for unexpected size changes during processing.

These are positive design decisions.

### Important architectural limitation

Android's Storage Access Framework does not guarantee that writing a document corresponds directly to overwriting the same physical storage blocks.

This is particularly relevant to:

- flash storage
- filesystem copy-on-write behavior
- provider implementations
- cloud-backed DocumentsProviders
- filesystem snapshots
- wear leveling

Therefore, the application cannot universally guarantee physical destruction of every historical copy of data.

This is not considered a flaw in the overwrite algorithm itself.

### Assessment

**🟡 Provider-dependent**

The implementation is careful, but the physical guarantees depend on the underlying storage and DocumentsProvider.

---

# 18. Android Manifest and Permissions

The application uses restrictive Android permissions.

Important positive properties include:

```text
android:allowBackup="false"
```

and the absence of broad storage-management permissions.

Internal Activities such as:

```text
HexEditorActivity
SettingsActivity
```

are not exported.

The launcher/main Activity is exported as required for normal Android interaction and file-sharing intents.

### Assessment

**🟢 Excellent**

---

# 19. `FLAG_SECURE`

The application enables:

```java
WindowManager.LayoutParams.FLAG_SECURE
```

This is appropriate for an application handling:

- passwords
- decrypted files
- sensitive metadata
- cryptographic information

It reduces exposure through screenshots and supported screen-capture mechanisms.

### Assessment

**🟢 Very Good**

---

# 20. Intent Handling

The application supports file-opening and sharing intents such as:

```text
ACTION_VIEW
ACTION_SEND
```

This increases the externally reachable input surface.

However, the application processes the received data through Android URI/content APIs rather than treating arbitrary external values as unrestricted filesystem paths.

No obvious arbitrary-file-access vulnerability was identified in this area.

### Assessment

**🟢 Good**

---

# 21. BinEd / Numeric Cast Finding

A previous static-analysis warning identified code similar to:

```java
valuesCache[0] = (byte) intValue;
```

A narrowing conversion can theoretically truncate a user-controlled integer.

However, the current implementation validates the input range before the conversion.

The relevant logic distinguishes signed and unsigned byte ranges before performing the cast.

Therefore, the cast itself is not sufficient evidence of an exploitable truncation vulnerability in the current implementation.

### Assessment

**🟢 Mitigated / No confirmed vulnerability**

The static-analysis warning should nevertheless remain monitored after future changes to this code.

---

# 22. Privacy of Security Reports

The application-generated security/deletion report contains device information such as:

- Manufacturer
- Device model
- Android version
- API level
- Build information
- Application version

This information can contribute to device fingerprinting.

However:

- the report is generated locally;
- the user chooses where it is saved;
- it is not automatically transmitted to a remote server.

### Finding: SFE-003

**Severity:** Low

### Recommendation

Keep the current behavior if the device information is useful for diagnostics, but clearly document what information is included in generated reports.

---

# 23. Application Architecture

The main architectural concern is the amount of responsibility concentrated in `MainActivity`.

It coordinates several independent security-sensitive areas:

```text
MainActivity
    |
    +-- UI
    +-- SAF
    +-- Encryption
    +-- Decryption
    +-- ZIP processing
    +-- Metadata processing
    +-- Secure deletion
    +-- Biometrics
    +-- Reporting
    +-- Operation state
    +-- Progress handling
```

This is not itself a vulnerability.

However, a large security-sensitive Activity increases the probability of future regressions.

### Finding: SFE-004

**Severity:** Medium

### Recommendation

Gradually separate responsibilities into dedicated components:

```text
MainActivity
    |
    +-- FileOperationManager
    +-- CryptoEngine
    +-- MetadataService
    +-- SecureDeleteManager
    +-- BiometricPasswordStore
    +-- ZipManager
    +-- ReportManager
```

This should be considered a maintainability and future-security improvement rather than an urgent vulnerability fix.

---

# 24. Dependency Security

The application relies on several security-relevant third-party libraries, including Bouncy Castle and AndroidX components.

The cryptographic implementation itself appears sound from source inspection.

However, dependency security should be evaluated independently through:

- dependency vulnerability scanning
- CVE monitoring
- Gradle dependency verification
- release artifact verification
- reproducible or controlled builds where practical

### Assessment

**🟡 Additional verification recommended**

This audit does not constitute a complete third-party supply-chain audit.

---

# 25. Security Findings Summary

| ID | Finding | Severity | Status |
|---|---|---|---|
| SFE-001 | Temporary files may survive abnormal termination | Low/Medium | 🟡 Recommended improvement |
| SFE-002 | `deleteOnExit()` should not be treated as a security guarantee on Android | Low | 🟡 Recommended improvement |
| SFE-003 | Security reports contain device fingerprinting information | Low | 🟡 Privacy consideration |
| SFE-004 | Large responsibility concentration in `MainActivity` | Medium | 🟡 Architectural improvement |
| SFE-005 | Secure overwrite cannot guarantee physical block destruction through SAF | Medium* | 🟡 Platform limitation |
| SFE-006 | BinEd numeric narrowing cast | Low | 🟢 Mitigated |
| SFE-007 | V1 uses legacy PBKDF2-based cryptography | — | 🟢 Accepted for compatibility |

\* This is a platform/storage limitation rather than a confirmed vulnerability in the implementation.

---

# 26. Security Strengths

The strongest aspects of S-File-Encryptor 3.1.9 are:

1. **Argon2id password-based key derivation**
2. **AES-256-GCM authenticated encryption**
3. **Authenticated V2 header**
4. **Strong random salt and nonce generation**
5. **Plaintext protection until GCM authentication**
6. **Private temporary storage**
7. **Two-pass validation/decryption strategy**
8. **Explicit sensitive-buffer cleanup**
9. **Android Keystore integration**
10. **Strong biometric authentication**
11. **ZIP traversal protection**
12. **ZIP resource limits**
13. **XML external-entity protection**
14. **Restrictive Android permissions**
15. **Storage Access Framework integration**
16. **`FLAG_SECURE`**
17. **Defensive MP4 parsing**
18. **Legacy V1 compatibility without weakening V2**
19. **No broad external-storage permission requirement**
20. **No obvious arbitrary command execution surface**

---

# 27. Recommended Next Steps

## Priority 1 — Improve Temporary-File Lifecycle

Implement stronger orphan detection and cleanup.

Recommended properties:

- Unique operation identifiers
- Explicit active-operation state
- Immediate cleanup on normal completion
- Cleanup on failure
- Startup orphan detection
- Age-based fallback cleanup

---

## Priority 2 — Adversarial Testing

Create a dedicated security test corpus containing:

### Cryptographic tests

- Truncated V2 files
- Modified V2 headers
- Modified Argon2 parameters
- Modified salt
- Modified nonce
- Modified ciphertext
- Modified authentication tag
- Wrong passwords
- Empty passwords
- Extremely long passwords
- Invalid UTF-8 edge cases

### ZIP tests

- Zip Slip paths
- Absolute paths
- `../`
- `..\`
- Duplicate entries
- ZIP bombs
- Excessive nesting
- Huge entry counts
- Huge declared sizes
- Malformed central directories

### XML tests

- XXE
- External DTD
- Entity expansion
- Malformed XML
- Extremely deep XML
- Oversized XML
- Malicious relationships

### MP4 tests

- Invalid atom sizes
- Integer overflow cases
- Truncated atoms
- Invalid offsets
- Recursive structures
- Extremely large metadata blocks

---

# 28. Interruption Testing

Security-sensitive file operations should be tested under unexpected termination.

Recommended scenarios:

- Application killed during encryption
- Application killed during decryption
- Process terminated after GCM authentication
- Process terminated before final output
- Force-stop
- Device reboot
- Activity recreation
- Low-storage condition
- Destination already exists
- Destination creation failure
- Temporary-file creation failure
- DocumentsProvider interruption
- Input URI becoming unavailable

The primary objective is to verify:

```text
No corrupted final file
No unintended plaintext exposure
No accidental deletion of the original
No permanent orphaned sensitive files
No false SUCCESS result
```

---

# 29. Dependency Audit

A future release-security workflow should include automated dependency analysis.

Recommended checks:

```text
Gradle dependency tree
        |
        v
Known vulnerability database
        |
        v
CVE / security advisory check
        |
        v
Release approval
```

This should be performed separately from source-code auditing.

---

# 30. Architecture Refactoring

The application would benefit from gradually moving security-sensitive operations out of `MainActivity`.

A possible architecture:

```text
                    MainActivity
                         |
              +----------+----------+
              |          |          |
              v          v          v
        FileManager   UI State   OperationManager
              |
      +-------+-------+
      |       |       |
      v       v       v
   Crypto   ZIP    Metadata
   Engine   Manager  Service
              |
              +------+
              |
              v
       SecureDeleteManager
```

This is not required to make the current implementation secure.

It is primarily intended to reduce future regression risk.

---

# 31. Final Assessment

The static audit of **S-File-Encryptor 3.1.9** found **no critical security vulnerability** in the reviewed implementation.

The application's V2 cryptographic architecture is particularly strong:

```text
User Password
      |
      v
    Argon2id
      |
      v
  AES-256-GCM
      |
      +--> Authenticated Header (AAD)
      |
      v
Encrypted File
```

The surrounding security architecture adds several important defensive layers:

```text
                 S-File-Encryptor
                        |
        +---------------+---------------+
        |               |               |
        v               v               v
     Argon2id        Android        Storage
                    Keystore        Access Framework
        |               |               |
        v               v               v
   AES-256-GCM      Biometrics      URI isolation
        |
        v
  Authenticated
     Header
        |
        v
 Private Temporary
      Storage
        |
        +--------+
        |        |
        v        v
      ZIP      XML
     Limits    Hardening
```

The remaining risks are primarily **peripheral and operational**, rather than weaknesses in the core encryption design.

The most important future improvements are:

1. Better temporary-file lifecycle management
2. Extensive interruption/crash testing
3. Adversarial parser/fuzz testing
4. Dependency vulnerability auditing
5. Gradual architectural separation of `MainActivity`

No recommendation from this audit requires replacing the current V2 cryptographic construction.

## Final Rating

**9.1 / 10 — Strong Security Architecture**

S-File-Encryptor 3.1.9 demonstrates a mature security-oriented design for a local Android file-encryption application, with particularly strong protection in its V2 cryptographic format, authenticated decryption pipeline, biometric key storage, ZIP processing, and Android permission model.

---

## Disclaimer

This report represents a static security review of the source code available in `S-File-Encryptor 3.1.9`.

It is not a formal penetration test, independent certification, cryptographic proof, hardware security evaluation, or guarantee that the application is free from all vulnerabilities.

Security properties of Android storage, flash memory, filesystem implementations, DocumentsProviders, device firmware, third-party libraries, and the underlying operating system may affect the practical security of certain features.

Future code changes should be subjected to additional security review and adversarial testing.
