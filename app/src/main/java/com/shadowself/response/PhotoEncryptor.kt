package com.shadowself.response

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PhotoEncryptor
 *
 * Encrypts and decrypts incident photos using AES-256-GCM.
 * The symmetric key is generated and stored exclusively in Android Keystore —
 * it never appears in memory as a raw byte array and cannot be extracted.
 *
 * Encrypted file format:
 *   [12 bytes]  GCM IV (random, unique per encryption)
 *   [4 bytes]   Ciphertext length (big-endian int)
 *   [N bytes]   AES-256-GCM ciphertext + 16-byte auth tag
 *
 * The auth tag is included automatically by the JCE GCM implementation.
 * Any tampering with the ciphertext will cause decryption to throw
 * AEADBadTagException before any plaintext is returned.
 *
 * Key alias is app-specific. If the key is ever deleted (factory reset,
 * keystore wipe), existing encrypted photos become permanently unreadable —
 * this is intentional. Forensic data should not survive key loss.
 */
@Singleton
class PhotoEncryptor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG           = "PhotoEncryptor"
        private const val KEY_ALIAS     = "shadowself_photo_key"
        private const val KEYSTORE      = "AndroidKeyStore"
        private const val ALGORITHM     = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_BYTES  = 12
        private const val GCM_TAG_BITS  = 128
    }

    init { ensureKeyExists() }

    // ── Encryption ────────────────────────────────────────────────────────────

    /**
     * Encrypts [inputFile] and writes the result to a new .enc file
     * in the same directory. Returns the encrypted file.
     */
    fun encryptFile(inputFile: File): File {
        val key     = loadKey()
        val cipher  = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv          = cipher.iv   // 12 random bytes
        val plaintext   = inputFile.readBytes()
        val ciphertext  = cipher.doFinal(plaintext)

        val encFile = File(inputFile.parent, inputFile.nameWithoutExtension + ".enc")
        encFile.outputStream().use { out ->
            out.write(iv)                                    // 12 bytes IV
            out.write(intToBytes(ciphertext.size))           // 4 bytes length
            out.write(ciphertext)                            // N bytes ciphertext + tag
        }
        Logger.d(TAG, "Encrypted: ${inputFile.name} → ${encFile.name} " +
                      "(${ciphertext.size} bytes)")
        return encFile
    }

    /**
     * Decrypts an .enc file back to a plaintext JPEG byte array.
     * Called by the UI when the owner views incident photos.
     * Never writes decrypted bytes to disk.
     */
    fun decryptToBytes(encFile: File): ByteArray {
        val bytes = encFile.readBytes()
        val iv    = bytes.copyOfRange(0, GCM_IV_BYTES)
        val len   = bytesToInt(bytes.copyOfRange(GCM_IV_BYTES, GCM_IV_BYTES + 4))
        val ciphertext = bytes.copyOfRange(GCM_IV_BYTES + 4, GCM_IV_BYTES + 4 + len)

        val key    = loadKey()
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)   // Throws AEADBadTagException if tampered
    }

    // ── Key management ────────────────────────────────────────────────────────

    private fun ensureKeyExists() {
        val ks = KeyStore.getInstance(KEYSTORE).also { it.load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            generateKey()
            Logger.d(TAG, "New photo encryption key generated")
        }
    }

    private fun generateKey() {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)   // Works from background service
            .setRandomizedEncryptionRequired(true)  // Forces unique IV per operation
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .also { it.init(spec) }
            .generateKey()
    }

    private fun loadKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).also { it.load(null) }
        return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(),
        (value shr 8).toByte(),  value.toByte()
    )

    private fun bytesToInt(b: ByteArray): Int =
        (b[0].toInt() and 0xFF shl 24) or (b[1].toInt() and 0xFF shl 16) or
        (b[2].toInt() and 0xFF shl 8)  or (b[3].toInt() and 0xFF)
}
