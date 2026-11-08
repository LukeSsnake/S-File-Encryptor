#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sfe_decrypt.py — Standalone decryptor for S-File Encryptor (Android) files.

Supported formats (full specification in FORMAT_SPECIFICATION.md,
repository: https://github.com/LukeSsnake/S-File-Encryptor):

  * V2 (current) : Argon2id + AES-256-GCM, 48-byte header used as AAD
  * V1 (legacy)  : PBKDF2-HMAC-SHA256 + AES-256-CBC + HMAC-SHA256 (EtM)

Usage:
    python sfe_decrypt.py <file.aes> [-o OUTPUT]

Dependencies:
    pip install cryptography argon2-cffi
    (PBKDF2 and HMAC use only the standard library)

Security properties mirrored from the Android app:
  * Authentication is verified BEFORE any plaintext reaches the final
    output: plaintext is written to a temporary file in the same
    directory as the destination and atomically renamed only after the
    GCM tag (V2) or the HMAC (V1) validates.
  * Wrong password and tampering produce the SAME error, by design
    (AEAD / Encrypt-then-MAC cannot distinguish them).
  * Header parameters are validated fail-closed before any key
    derivation is attempted.
  * Large files are processed via streaming (the whole file is never
    loaded into RAM).
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import hmac
import os
import sys
import tempfile
from pathlib import Path

try:
    from argon2.low_level import Type as _Argon2Type, hash_secret_raw
    from argon2.exceptions import Argon2Error
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers import (
        Cipher, algorithms, modes,
    )
except ImportError:
    sys.stderr.write(
        "Missing dependencies. Install with:\n"
        "    pip install cryptography argon2-cffi\n"
    )
    sys.exit(2)


# ==========================================================================
# Format constants — must match CryptoEngine.java exactly
# ==========================================================================

V2_MAGIC = b"SFE2"

V2_HEADER_SIZE = 48          # magic(4) ver(1) kdf(1) cipher(1) rsv(1)
                             # + mem(4) iter(4) paral(4) salt(16) nonce(12)
V2_VERSION = 2
V2_KDF_ARGON2ID = 1
V2_CIPHER_AES_256_GCM = 1
V2_SALT_OFFSET = 20
V2_NONCE_OFFSET = 36
V2_TAG_SIZE = 16
V2_KEY_SIZE = 32

ARGON2_VERSION_13 = 0x13

# Accepted ranges (defensive limits, identical to the app's).
ARGON2_MIN_MEMORY_KIB = 8 * 1024
ARGON2_MAX_MEMORY_KIB = 128 * 1024
ARGON2_MIN_ITERATIONS = 1
ARGON2_MAX_ITERATIONS = 6
ARGON2_MIN_PARALLELISM = 1
ARGON2_MAX_PARALLELISM = 2

# V1 (legacy): salt(16) || IV(16) || ciphertext || HMAC(32)
V1_SALT_SIZE = 16
V1_IV_SIZE = 16
V1_HMAC_SIZE = 32
V1_KEY_SIZE = 32
V1_DERIVED_SIZE = 64
V1_ITERATIONS = 100_000

BUFFER_SIZE = 1024 * 1024    # 1 MiB (multiple of 16)


class DecryptError(Exception):
    """Base decryption error."""


class AuthenticationFailed(DecryptError):
    """Wrong password OR tampered/corrupted file."""


class InvalidFile(DecryptError):
    """Structural problem: not an SFE file or malformed header."""


# ==========================================================================
# KDFs
# ==========================================================================

def derive_argon2id(password: bytes, salt: bytes, memory_kib: int,
                    iterations: int, parallelism: int) -> bytes:
    """Argon2id v1.3, raw 32-byte output — identical to the app."""
    try:
        return hash_secret_raw(
            secret=password,
            salt=salt,
            time_cost=iterations,
            memory_cost=memory_kib,
            parallelism=parallelism,
            hash_len=V2_KEY_SIZE,
            type=_Argon2Type.ID,
            version=ARGON2_VERSION_13,
        )
    except Argon2Error as exc:
        raise InvalidFile(f"Argon2 derivation failed: {exc}") from exc


def derive_v1_keys(password: bytes, salt: bytes) -> tuple:
    """PBKDF2-HMAC-SHA256, 100k iterations, 64 bytes -> (aes, hmac)."""
    derived = hashlib.pbkdf2_hmac(
        "sha256", password, salt, V1_ITERATIONS, V1_DERIVED_SIZE
    )
    return derived[:V1_KEY_SIZE], derived[V1_KEY_SIZE:]


# ==========================================================================
# Helpers
# ==========================================================================

def parse_v2_header(header: bytes) -> tuple:
    """Validate the 48-byte header (fail-closed).

    Returns (memory_kib, iterations, parallelism, salt, nonce).
    """
    if len(header) != V2_HEADER_SIZE:
        raise InvalidFile("Header truncated.")

    if header[0:4] != V2_MAGIC:
        raise InvalidFile("Invalid V2 magic.")

    version = header[4]
    kdf = header[5]
    cipher_id = header[6]
    reserved = header[7]

    if reserved != 0:
        raise InvalidFile("Invalid V2 header (reserved byte).")
    if version != V2_VERSION:
        raise InvalidFile("Unsupported file version.")
    if kdf != V2_KDF_ARGON2ID:
        raise InvalidFile("Unsupported KDF.")
    if cipher_id != V2_CIPHER_AES_256_GCM:
        raise InvalidFile("Unsupported cipher.")

    memory_kib = int.from_bytes(header[8:12], "big")
    iterations = int.from_bytes(header[12:16], "big")
    parallelism = int.from_bytes(header[16:20], "big")

    if not (ARGON2_MIN_MEMORY_KIB <= memory_kib <= ARGON2_MAX_MEMORY_KIB):
        raise InvalidFile("Invalid memory parameter.")
    if not (ARGON2_MIN_ITERATIONS <= iterations <= ARGON2_MAX_ITERATIONS):
        raise InvalidFile("Invalid iteration parameter.")
    if not (ARGON2_MIN_PARALLELISM <= parallelism <= ARGON2_MAX_PARALLELISM):
        raise InvalidFile("Invalid parallelism parameter.")

    salt = header[V2_SALT_OFFSET:V2_SALT_OFFSET + 16]
    nonce = header[V2_NONCE_OFFSET:V2_NONCE_OFFSET + 12]
    return memory_kib, iterations, parallelism, salt, nonce


def pkcs7_strip_final_block(block: bytes) -> bytes:
    """Validate and strip PKCS#5/#7 padding from the last block (16 B)."""
    if len(block) != 16:
        raise InvalidFile("Unexpected V1 structure.")
    pad = block[-1]
    if pad < 1 or pad > 16:
        raise InvalidFile("Invalid V1 data (padding).")
    if block[len(block) - pad:] != bytes([pad]) * pad:
        raise InvalidFile("Invalid V1 data (padding).")
    return block[:len(block) - pad]


def stage_in_output_dir(output_path: Path):
    """Temp file in the SAME directory as the destination, so the final
    rename is atomic on the same filesystem."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    return tempfile.mkstemp(
        prefix=".sfe-", suffix=".tmp", dir=str(output_path.parent)
    )


# ==========================================================================
# V2 — Argon2id + AES-256-GCM
# ==========================================================================

def decrypt_v2(input_path: Path, password: str, output_path: Path) -> None:
    file_size = input_path.stat().st_size
    if file_size < V2_HEADER_SIZE + V2_TAG_SIZE:
        raise InvalidFile("Invalid or corrupted file.")

    with open(input_path, "rb") as fin:
        header = fin.read(V2_HEADER_SIZE)
        memory_kib, iterations, parallelism, salt, nonce = \
            parse_v2_header(header)

        ciphertext_len = file_size - V2_HEADER_SIZE - V2_TAG_SIZE

        # The tag sits at the end of the file and must be supplied to the
        # GCM constructor (the 'cryptography' library does not accept it
        # through the stream). Reading it here does NOT release any
        # plaintext: finalize() still validates the tag before anything
        # is moved to the final destination.
        fin.seek(-V2_TAG_SIZE, os.SEEK_END)
        tag = fin.read(V2_TAG_SIZE)
        if len(tag) != V2_TAG_SIZE:
            raise InvalidFile("Truncated file (tag).")

        key = derive_argon2id(
            password.encode("utf-8"),
            salt,
            memory_kib,
            iterations,
            parallelism,
        )

        # AES-256-GCM; ENTIRE header as AAD; tag verified by finalize().
        decryptor = Cipher(
            algorithms.AES(key), modes.GCM(nonce, tag)
        ).decryptor()
        decryptor.authenticate_additional_data(header)

        fd, tmp_path = stage_in_output_dir(output_path)
        try:
            with os.fdopen(fd, "wb") as fout:
                fin.seek(V2_HEADER_SIZE, os.SEEK_SET)

                remaining = ciphertext_len
                while remaining > 0:
                    chunk = fin.read(min(BUFFER_SIZE, remaining))
                    if not chunk:
                        raise InvalidFile("Truncated file.")
                    fout.write(decryptor.update(chunk))
                    remaining -= len(chunk)

                # finalize() verifies the tag. Failure means wrong
                # password or tampered/corrupted file.
                try:
                    tail = decryptor.finalize()
                    if tail:
                        fout.write(tail)
                except InvalidTag:
                    raise AuthenticationFailed from None

                fout.flush()
                os.fsync(fout.fileno())

            # Layout is header || ciphertext || tag: after streaming
            # exactly ciphertext_len bytes from offset 48 and reading the
            # tag from the end, the file is fully consumed. Nothing more
            # to check.

            # Authenticated: atomic delivery of the plaintext.
            os.replace(tmp_path, output_path)

        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)


# ==========================================================================
# V1 (legacy) — PBKDF2 + AES-256-CBC + HMAC-SHA256
# ==========================================================================

def decrypt_v1(input_path: Path, password: str, output_path: Path) -> None:
    file_size = input_path.stat().st_size
    overhead = V1_SALT_SIZE + V1_IV_SIZE + V1_HMAC_SIZE
    if file_size < overhead + 16:
        raise InvalidFile("Invalid or corrupted file.")

    ciphertext_len = file_size - overhead
    if ciphertext_len % 16 != 0:
        raise InvalidFile("Invalid V1 length.")

    with open(input_path, "rb") as fin:
        salt = fin.read(V1_SALT_SIZE)
        iv = fin.read(V1_IV_SIZE)

        aes_key, mac_key = derive_v1_keys(password.encode("utf-8"), salt)

        # ---- Pass 1: HMAC over salt || IV || ciphertext -------------------
        mac = hmac.new(mac_key, digestmod=hashlib.sha256)
        mac.update(salt)
        mac.update(iv)

        remaining = ciphertext_len
        while remaining > 0:
            chunk = fin.read(min(BUFFER_SIZE, remaining))
            if not chunk:
                raise InvalidFile("Truncated file.")
            mac.update(chunk)
            remaining -= len(chunk)

        stored_hmac = fin.read(V1_HMAC_SIZE)
        if len(stored_hmac) != V1_HMAC_SIZE:
            raise InvalidFile("Truncated file (HMAC).")

        if not hmac.compare_digest(mac.digest(), stored_hmac):
            raise AuthenticationFailed from None

        # ---- Pass 2: AES-256-CBC decryption --------------------------------
        fin.seek(V1_SALT_SIZE + V1_IV_SIZE)
        decryptor = Cipher(algorithms.AES(aes_key), modes.CBC(iv)).decryptor()

        fd, tmp_path = stage_in_output_dir(output_path)
        try:
            with os.fdopen(fd, "wb") as fout:
                # PKCS#7 padding occupies only the LAST 16-byte block.
                # We always hold the last 16 bytes of plaintext pending and
                # only write them once the next block arrives; at the end,
                # 'pending' is exactly the final (padded) block.
                pending = b""
                processed = 0

                while processed < ciphertext_len:
                    want = min(BUFFER_SIZE, ciphertext_len - processed)
                    chunk = fin.read(want)
                    if not chunk:
                        raise InvalidFile("Truncated file.")
                    processed += len(chunk)

                    plain = decryptor.update(chunk)
                    if plain:
                        buf = pending + plain
                        pending = buf[-16:]
                        fout.write(buf[:len(buf) - 16])

                # Chunks are multiples of 16, so finalize() returns empty;
                # the guard below covers any structural deviation.
                tail = decryptor.finalize()
                final_block = pending + tail
                fout.write(pkcs7_strip_final_block(final_block))

                fout.flush()
                os.fsync(fout.fileno())

            # Authenticated (HMAC) and valid padding: atomic delivery.
            os.replace(tmp_path, output_path)

        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)


# ==========================================================================
# Command-line interface
# ==========================================================================

def default_output_for(input_path: Path) -> Path:
    name = input_path.name
    if name.lower().endswith(".aes"):
        return input_path.with_name(name[:len(name) - 4])
    return input_path.with_name(name + "_decrypted")


def decrypt_file(input_path: Path, password: str, output_path: Path) -> str:
    if not input_path.is_file():
        raise InvalidFile(f"Input file not found: {input_path}")
    if input_path.stat().st_size < 1:
        raise InvalidFile("Empty file.")

    with open(input_path, "rb") as fin:
        head = fin.read(4)
    version = "V2" if head == V2_MAGIC else "V1"

    if version == "V2":
        decrypt_v2(input_path, password, output_path)
    else:
        decrypt_v1(input_path, password, output_path)

    return version


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Decrypt S-File Encryptor files (V2 and V1)."
    )
    parser.add_argument("file", help="The .aes file to decrypt")
    parser.add_argument(
        "-o", "--output",
        help="Output file (default: input name without the .aes extension; "
             "if the input does not end in .aes, appends '_decrypted')",
    )
    args = parser.parse_args()

    input_path = Path(args.file)
    output_path = Path(args.output) if args.output else default_output_for(
        input_path
    )

    # The password is never passed via command-line arguments (it would show
    # up in the process list).
    if sys.stdin.isatty():
        password = getpass.getpass("Password: ")
    else:
        password = sys.stdin.readline().rstrip("\n")

    try:
        version = decrypt_file(input_path, password, output_path)
    except AuthenticationFailed:
        sys.stderr.write(
            "ERROR: wrong password, or the file was tampered/corrupted.\n"
        )
        return 1
    except InvalidFile as exc:
        sys.stderr.write(f"ERROR: {exc}\n")
        return 1
    except OSError as exc:
        sys.stderr.write(f"I/O ERROR: {exc}\n")
        return 1

    print(f"OK ({version}): file decrypted to {output_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
