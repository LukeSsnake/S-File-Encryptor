# S-File-Encryptor — Threat Model

## 1. Purpose

This document describes the security model of **S-File-Encryptor**, the threats it is designed to mitigate, the security mechanisms used to address those threats, and the limitations of those protections.

The purpose of this document is to provide a clear and realistic description of what the application protects against and what it does not.

S-File-Encryptor is designed primarily to protect the confidentiality and integrity of files against unauthorized access when an attacker obtains the encrypted file but does not know the correct password.

---

## 2. Security Goals

The primary security goals are:

- Protect the confidentiality of encrypted file contents.
- Detect unauthorized modification of encrypted files.
- Detect corruption and truncation of encrypted files.
- Make offline password-guessing attacks more expensive.
- Minimize unnecessary exposure of plaintext data during encryption and decryption.
- Protect sensitive application data using Android platform security mechanisms where possible.
- Avoid providing hidden or alternative methods of recovering encrypted data without the correct password.

---

## 3. Protected Assets

The main assets considered by this threat model are:

- Plaintext files selected for encryption.
- Encrypted files.
- Passwords used to encrypt and decrypt files.
- Cryptographic keys derived from passwords.
- Temporary plaintext data created during certain operations.
- Authentication and integrity data associated with encrypted files.
- File metadata when the metadata-removal feature is used.

---

## 4. Threat Model

S-File-Encryptor primarily considers attackers who may obtain, copy, modify, or attempt to analyze encrypted files without knowing the correct password.

The following attacker scenarios are considered.

### 4.1 Attacker with access to an encrypted file

An attacker may obtain a copy of an encrypted file through:

- File sharing.
- Cloud storage.
- Removable storage.
- Backup copies.
- Device theft.
- Unauthorized access to a storage location.

The attacker is assumed to have complete access to the encrypted file and may analyze it offline.

The security objective in this scenario is to prevent recovery of the original plaintext without the correct password.

---

### 4.2 Attacker who modifies the encrypted file

An attacker may modify, replace, truncate, or otherwise manipulate an encrypted file.

The application is designed to detect unauthorized modifications rather than silently decrypt modified data.

For the V2 format, AES-256-GCM authentication is used and the file header is authenticated as additional authenticated data (AAD).

Authentication failure causes the file to be rejected.

---

### 4.3 Offline password-guessing attacker

An attacker with a copy of an encrypted file may perform password guesses without interacting with the application.

This threat cannot be completely eliminated because password-based encryption ultimately depends on the strength of the user's password.

S-File-Encryptor uses a memory-hard password derivation function for the V2 format to increase the cost of large-scale password guessing.

The application does not rely on server-side authentication or an online rate limiter to protect encrypted files.

Therefore, password strength remains an important part of the security model.

---

### 4.4 Attacker with a stolen or lost device

If an attacker obtains a device containing encrypted files, the encrypted files are intended to remain protected by the encryption password.

Android platform security mechanisms are also used to reduce unnecessary exposure of application data.

However, encryption does not guarantee protection if the attacker already knows the password or can obtain the password through another mechanism.

---

### 4.5 Malicious application on the device

The Android operating system provides application isolation and permission boundaries.

S-File-Encryptor does not request broad storage access and uses Android's Storage Access Framework (SAF) for user-selected files and destinations.

These mechanisms reduce the amount of storage that the application can access without explicit user interaction.

However, the threat model does not assume that every other application on the device is maliciously incapable of exploiting an operating-system vulnerability.

---

### 4.6 Compromised or rooted device

A device with root access, a compromised operating system, or other privileged compromise is not considered a fully trusted environment.

An attacker with sufficient privileges may potentially:

- Inspect application memory.
- Observe data while it is being processed.
- Capture passwords while they are entered.
- Modify application behavior.
- Access protected application resources.
- Interfere with the operating system itself.

Application-level cryptographic protections cannot guarantee confidentiality against an attacker who has complete control of the operating system.

S-File-Encryptor may perform security checks for certain compromised environments, but these checks must not be interpreted as a guarantee that a compromised device is secure.

---

## 5. Cryptographic Protection

### 5.1 V2 Format

The current encryption format uses:

- **AES-256-GCM** for authenticated encryption.
- **Argon2id** for password-based key derivation.
- A random salt.
- A random nonce.
- Authentication of the encrypted file header as AAD.
- A 256-bit encryption key.

AES-GCM provides both confidentiality and integrity/authentication.

Argon2id increases the computational and memory cost of password guessing compared with conventional fast password-hashing functions.

---

### 5.2 V1 Compatibility

S-File-Encryptor also supports decryption of the legacy V1 format.

The V1 format uses:

- AES-256-CBC.
- HMAC-SHA256.
- PBKDF2-HMAC-SHA256.
- A random salt.
- A random IV.

V1 exists for backward compatibility with files created by earlier versions of the application.

New encryption operations use the V2 format.

V1 and V2 should therefore be considered separate cryptographic formats with different security properties.

---

## 6. File Integrity

A successful decryption must not be interpreted merely as "some bytes could be decrypted."

The application verifies the authentication/integrity information associated with the encrypted file.

For V2, AES-GCM authentication protects both the ciphertext and the authenticated header.

The application also performs structural validation of the encrypted file.

This allows the application to reject conditions such as:

- Invalid headers.
- Unsupported parameters.
- Incorrect authentication data.
- Modified ciphertext.
- Truncated files.
- Invalid file structure.
- Unexpected trailing data.

---

## 7. Plaintext Exposure During Decryption

A particularly important security property is preventing unauthenticated plaintext from being presented as a successfully decrypted file.

For V2 decryption, plaintext is first processed through a private temporary file.

The final destination is written only after successful authentication and validation of the encrypted data.

This design reduces the risk of leaving a partially decrypted or unauthenticated output file when authentication fails.

Temporary files are also subject to cleanup after the operation.

---

## 8. Password Security

The password is a critical component of the security model.

There is no password recovery mechanism, master password, or hidden recovery key.

The correct password is required to derive the cryptographic key needed to decrypt the file.

Users should therefore:

- Use strong and unique passwords.
- Avoid predictable passwords.
- Avoid reusing important passwords.
- Store passwords in a secure location.
- Maintain a secure backup of passwords when appropriate.

A strong encryption algorithm cannot compensate for a weak or compromised password.

---

## 9. Biometric Authentication

Biometric authentication is provided as a convenience feature.

It is not intended to replace the underlying encryption password.

The biometric mechanism can provide convenient access to a stored password through Android's security mechanisms, but the encrypted file itself remains dependent on the cryptographic key derived from the password.

Users should continue to maintain a secure copy of their password.

---

## 10. Application-Level Protections

S-File-Encryptor uses several Android security mechanisms to reduce exposure of sensitive information.

These include, where applicable:

- Android Storage Access Framework for user-selected files.
- Private application storage for temporary data.
- `FLAG_SECURE` to reduce screen-capture exposure.
- Avoidance of unnecessary broad storage permissions.
- Secure cleanup of sensitive in-memory buffers where practical.
- Temporary-file cleanup.
- Authentication of encrypted file contents.
- Validation of encrypted file structure.
- Restricted application components where appropriate.
- Android Keystore-backed protection for biometric password storage.

These mechanisms provide defense in depth but do not replace the cryptographic security of the encrypted file format.

---

## 11. Threat Mitigation Matrix

| Threat | Mitigation | Protection Level |
|---|---|---|
| Attacker obtains encrypted file | AES-256 encryption | Strong |
| Unauthorized modification | AES-GCM authentication / HMAC for V1 | Strong |
| Header manipulation | Authenticated V2 header | Strong |
| File truncation | Structural and authentication checks | Strong |
| Invalid/corrupted file | Validation and authentication | Strong |
| Offline password guessing | Argon2id in V2 | Reduced attack efficiency |
| Weak user password | Password policy and user guidance | Partial |
| Temporary plaintext exposure | Private temporary processing and cleanup | Reduced |
| Screen capture | `FLAG_SECURE` | Reduced |
| Unauthorized storage access | SAF and Android permission model | Reduced |
| Malicious application | Android application isolation | Partial |
| Rooted/compromised device | Security checks and platform protections | Not guaranteed |
| Malware capturing the password | No application-level guarantee | Not mitigated |
| Forgotten password | No recovery mechanism | Not recoverable |

---

## 12. Out of Scope

The following threats are outside the guarantees of this application.

### 12.1 Compromised operating system

The application cannot guarantee confidentiality if an attacker has complete control of the operating system.

### 12.2 Password compromise

If an attacker obtains the user's password, the encryption cannot protect the data from that attacker.

### 12.3 Weak passwords

A predictable or commonly used password may be vulnerable to dictionary or password-guessing attacks.

### 12.4 Malware observing input

A sufficiently privileged or malicious environment may capture passwords while they are being entered.

Application-level encryption cannot prevent a compromised device from observing plaintext before encryption or after successful decryption.

### 12.5 Physical attacks against hardware

This threat model does not provide guarantees against advanced hardware attacks, forensic extraction techniques, or attacks requiring specialized laboratory equipment.

### 12.6 Operating-system vulnerabilities

Security vulnerabilities in Android, device firmware, hardware, or third-party components may fall outside the control of the application.

---

## 13. Metadata

Encryption of file contents does not necessarily hide all information about the file.

Depending on the storage environment and file format, information such as:

- File name.
- File size.
- File timestamps.
- File location.
- Filesystem metadata.

may remain visible outside the encrypted content.

The metadata-removal functionality is intended to remove supported metadata from files before they are stored or shared, but it should not be interpreted as a guarantee that every piece of metadata from every storage system or file format can be eliminated.

---

## 14. Secure Deletion

The application provides functionality intended to securely overwrite and delete files where supported by the underlying storage provider.

However, application-level deletion cannot guarantee physical destruction of data on every storage technology.

Storage devices, filesystems, storage providers, snapshots, backups, wear-leveling systems, and other underlying mechanisms may affect how deletion and overwriting behave.

The secure deletion functionality should therefore be understood as an attempt to overwrite and remove the selected file through the available storage interface, rather than as a guarantee of physical destruction of every underlying storage cell.

---

## 15. Trust Boundaries

The primary trust boundaries are:

```text
User
 │
 │ Password / File Selection
 ▼
S-File-Encryptor
 │
 ├── Password processing
 │
 ├── Key derivation
 │
 ├── Encryption / Decryption
 │
 └── Temporary processing
 │
 ▼
Android Storage Access Framework
 │
 ▼
User-selected storage
```

During encryption:

```text
Plaintext
    │
    ▼
Argon2id
    │
    ▼
AES-256-GCM
    │
    ▼
Authenticated ciphertext
```

During V2 decryption:

```text
Encrypted file
      │
      ▼
Header validation
      │
      ▼
Argon2id
      │
      ▼
AES-256-GCM verification
      │
      ├── Authentication failure
      │          │
      │          ▼
      │       Reject file
      │
      ▼
Authenticated plaintext
      │
      ▼
Temporary processing
      │
      ▼
Final output
```

---

## 16. Security Assumptions

The security model assumes:

1. The cryptographic primitives are implemented correctly.
2. The underlying cryptographic libraries behave according to their specifications.
3. Android's security mechanisms are functioning as intended.
4. The user's password remains secret.
5. The attacker does not have complete control of the operating system.
6. The user obtains the application from a trusted distribution source.
7. The encrypted file has not been compromised together with the user's password.

If these assumptions are violated, the security guarantees may be reduced or eliminated.

---

## 17. Security Limitations

No application can guarantee absolute security.

S-File-Encryptor is designed to provide strong cryptographic protection for files, but its security depends on multiple layers:

- Cryptographic algorithm security.
- Correct implementation.
- Password strength.
- Device security.
- Android security.
- Storage security.
- User behavior.
- Security of the software distribution channel.

The application should therefore be considered one component of a broader security strategy rather than a complete security solution by itself.

---

## 18. Security Philosophy

S-File-Encryptor follows several principles:

- Use established cryptographic primitives.
- Prefer authenticated encryption for new formats.
- Make offline password guessing more expensive.
- Fail closed when authentication or validation fails.
- Avoid silently accepting corrupted encrypted data.
- Minimize unnecessary plaintext exposure.
- Avoid hidden recovery mechanisms.
- Be transparent about security limitations.
- Preserve compatibility with legacy encrypted files without silently weakening the current encryption format.

---

## 19. Reporting Security Issues

If you discover a potential security vulnerability in S-File-Encryptor, please report it responsibly.

Include:

- A description of the vulnerability.
- The affected component or version.
- Steps to reproduce the issue.
- The potential security impact.
- Any relevant proof of concept or technical details.

Please avoid publicly disclosing a vulnerability before there has been an opportunity to investigate and address it.

---

## 20. Conclusion

S-File-Encryptor is designed to protect encrypted files primarily against unauthorized access, offline analysis, and unauthorized modification when the attacker does not possess the correct password and does not have complete control of the device.

The V2 format uses Argon2id and AES-256-GCM to provide password-based authenticated encryption.

However, encryption cannot compensate for a compromised password, a compromised operating system, malware capable of observing sensitive data, or vulnerabilities in the underlying platform.

The security model therefore intentionally distinguishes between threats that can be mitigated by the application and threats that are fundamentally outside the application's control.