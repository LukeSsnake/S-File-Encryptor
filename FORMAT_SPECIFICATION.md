# S-File Encryptor — File Format Specification

**Document version:** 1.0
**Applies to:** S-File Encryptor (as of the version that writes `.aes` files in the V2 format)
**Reference implementation:** [`CryptoEngine.java`](CryptoEngine.java)
**Purpose:** this document fully defines the binary structure of files encrypted by S-File Encryptor, in a platform-independent way, enabling third parties to build interoperable decryption tools (such as the standalone `sfe_decrypt.py` script).

---

## 1. Overview

Two formats exist:

| Format | KDF | Cipher | Authentication | Status |
|---|---|---|---|---|
| **V2** (current) | Argon2id | AES-256-GCM | GCM AEAD (integrated tag) | Written by new files |
| **V1** (legacy) | PBKDF2-HMAC-SHA256 | AES-256-CBC | HMAC-SHA256 (Encrypt-then-MAC) | Read-only |

New files are **always** written in V2. Decryption detects the format automatically from the first 4 bytes (magic).

All offsets and sizes in this specification are in **bytes**.

---

## 2. V2 format

### 2.1 Overall structure

```
+------------------+----------------------+------------+
| Header (48 B)    | Ciphertext (variable)| Tag (16 B) |
+------------------+----------------------+------------+
```

Total file size = `48 + ciphertext_length + 16`.

A valid V2 file has **at least** `48 + 16 = 64` bytes.

### 2.2 Header (48 bytes)

| Offset | Size | Field | Description |
|---|---|---|---|
| 0–3 | 4 | `magic` | ASCII bytes `"SFE2"` (`0x53 0x46 0x45 0x32`) |
| 4 | 1 | `version` | Must be `2` |
| 5 | 1 | `kdf` | Must be `1` (Argon2id) |
| 6 | 1 | `cipher` | Must be `1` (AES-256-GCM) |
| 7 | 1 | `reserved` | Must be `0` |
| 8–11 | 4 | `memory_kib` | Argon2 memory, in KiB — **big-endian**, unsigned 32-bit integer |
| 12–15 | 4 | `iterations` | Argon2 passes — **big-endian** |
| 16–19 | 4 | `parallelism` | Argon2 lanes — **big-endian** |
| 20–35 | 16 | `salt` | Random salt from a CSPRNG |
| 36–47 | 12 | `nonce` | Random nonce from a CSPRNG |

**Notes:**
- All multi-byte integers are encoded in **big-endian**.
- Bytes 8–19 are read as unsigned 32-bit integers (the reference implementation treats them as Java `int` values constrained by the ranges below; implementers should treat values above 2³¹−1 as out of range).

### 2.3 Argon2id parameters

| Parameter | Default (writing) | Accepted range (reading) |
|---|---|---|
| Type / version | Argon2id, version 1.3 (`0x13`) | Fixed |
| Memory | 65536 KiB (64 MiB) | 8192 – 131072 KiB (inclusive) |
| Iterations (passes) | 3 | 1 – 6 (inclusive) |
| Parallelism (lanes) | 1 | 1 – 2 (inclusive) |
| Derived output | 32 bytes (AES-256 key) | Fixed |

The KDF uses no additional *secret key* or *associated data* — only `password`, `salt`, and the parameters above.

**Header validation rules (reference implementation):**

1. `magic` ≠ `"SFE2"` → not V2 (falls back to V1 detection).
2. `reserved` ≠ 0 → invalid file.
3. `version`, `kdf`, or `cipher` outside the defined values → invalid file (unsupported).
4. Any parameter outside the accepted range → invalid file.

These validations are fail-closed: key derivation is never attempted with out-of-range parameters (protection against denial of service via malicious headers).

### 2.4 Key derivation

```
key = Argon2id(
    password = UTF-8(password),
    salt     = salt (header bytes 20–35),
    version  = 0x13,
    t        = iterations,
    m        = memory_kib (KiB),
    p        = parallelism,
    length   = 32 bytes
)
```

The password is converted to UTF-8 before derivation (passwords with accents/emoji behave identically on any platform that follows this specification).

### 2.5 Encryption and authentication

- **Cipher:** AES-256-GCM per NIST SP 800-38D.
- **Nonce:** 96 bits (12 bytes, header bytes 36–47).
- **Tag:** 128 bits (16 bytes), placed at the **end** of the file (not concatenated behind each block).
- **AAD (Additional Authenticated Data):** **the complete 48-byte header**. This authenticates the magic, version, KDF, cipher and — critically — the Argon2 parameters, salt, and nonce. An attacker cannot modify any header parameter without invalidating the tag.

**To encrypt:**

```
ciphertext || tag = AES-256-GCM-Encrypt(key, nonce, aad = header(48 B), plaintext)
```

**To decrypt:**

```
plaintext = AES-256-GCM-Decrypt(key, nonce, aad = header(48 B), ciphertext, tag)
```

Decryption is only considered successful after tag validation. A tag failure indicates a **wrong password, or a tampered/corrupted file** — the two cases cannot be distinguished (a desirable property of GCM).

### 2.6 Recommended sequencing for streaming implementations

The reference implementation guarantees that **no plaintext is released before authentication** by:

1. Reading and validating the header in one pass; deriving the key.
2. Decrypting the ciphertext into a **temporary file** in the app's private storage.
3. Processing the tag and validating it at `doFinal()`.
4. Only after success, copying the temp file to its final destination.

Command-line tools can replicate this by writing to `output.tmp` and atomically renaming after tag validation.

---

## 3. V1 format (legacy — read-only)

> V1 is kept for compatibility with files created by older versions of the app. **New files are never written in V1.**

### 3.1 Overall structure

```
+-------------+-----------+------------------------+---------------+
| Salt (16 B) | IV (16 B) | Ciphertext (variable)  | HMAC (32 B)   |
+-------------+-----------+------------------------+---------------+
```

Minimum size: `16 + 16 + 16 + 32 = 80` bytes. The ciphertext length is always a multiple of 16 (AES-CBC blocks).

### 3.2 Fields

| Field | Size | Description |
|---|---|---|
| `salt` | 16 | PBKDF2 salt |
| `iv` | 16 | AES-CBC initialization vector |
| `ciphertext` | multiple of 16, ≥ 16 | AES-256-CBC with PKCS#5/#7 padding |
| `hmac` | 32 | HMAC-SHA256 MAC |

### 3.3 Key derivation

```
derived = PBKDF2-HMAC-SHA256(
    password   = UTF-8(password),
    salt       = salt,
    iterations = 100000,
    dkLen      = 64 bytes
)
aes_key  = derived[0..31]
hmac_key = derived[32..63]
```

### 3.4 Authentication (Encrypt-then-MAC)

```
hmac = HMAC-SHA256(hmac_key, salt || iv || ciphertext)
```

Comparison must be performed in **constant time** (equivalent to `MessageDigest.isEqual`). HMAC failure = wrong password or tampered file. The HMAC does **not** cover the `hmac` field itself, obviously.

After validating the HMAC, decrypt the ciphertext with AES-256-CBC and strip the PKCS#5/#7 padding; invalid padding indicates corruption.

### 3.5 V1 detection

Any file whose first 4 bytes are not `"SFE2"` is treated as V1. Since V1 starts with a random salt, collision with the magic is unlikely (~2⁻³²), but implementers should validate the V1 structure (multiple-of-16 size, valid HMAC) before accepting the result.

---

## 4. Security considerations

- **Passwords:** the app requires a minimum of 12 characters for **new** encryptions; decryption accepts shorter legacy passwords to preserve compatibility with old files. Third-party tools do not need to enforce a minimum length.
- **96-bit random nonce:** V2 relies on nonce randomness (no counter). Each file uses a fresh nonce generated by a CSPRNG. With AES-GCM, never reuse a (key, nonce) pair — since the key derives from password + random 16-byte salt, the probability of reuse is negligible in practice.
- **Fail-closed:** out-of-range parameters, a non-zero reserved byte, unknown versions, and invalid structure must abort without attempting key derivation.
- **No metadata in the format:** the V2 file reveals nothing beyond the plaintext length (and the KDF parameters). There is no original filename, timestamp, or any other embedded metadata.

---

## 5. Interoperability

Reference Python tool (supports V2 and V1): [`sfe_decrypt.py`](sfe_decrypt.py).

Its dependencies: `cryptography` (AES-GCM, AES-CBC) and `argon2-cffi` (Argon2id). PBKDF2 and HMAC use only the standard library.

To validate your own implementation:

1. Encrypt a small file (e.g., 1 KiB) with the app and decrypt it with your tool.
2. Repeat with a large file (> 64 MiB) to exercise the streaming path.
3. Corrupt 1 byte of the ciphertext → decryption must fail authentication.
4. Corrupt 1 byte of the header (e.g., `memory_kib`) → must also fail, since the header is AAD.
5. Test a wrong password → must fail without releasing plaintext.
6. Test a legacy V1 file, if available.
