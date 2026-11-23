package com.superfiles.app.data

import com.android.apksig.ApkVerifier
import java.io.File

/** Result of cryptographically verifying an APK's signatures with apksig.
 *  Presence (which schemes exist) is [SignatureStatus]; this says whether the
 *  signatures that are there actually check out. */
data class SigVerification(
    val verified: Boolean,
    val v1Verified: Boolean,
    val v2Verified: Boolean,
    val v3Verified: Boolean,
    val errors: List<String>,
)

object SignatureCheck {

    /** Verifies the APK's signatures. Slow on big APKs (it hashes every entry),
     *  so callers must run this on a background thread. Never throws: a failure
     *  to verify comes back as verified = false with the reason in [SigVerification.errors]. */
    fun verify(apk: File): SigVerification {
        return runCatching {
            val result = ApkVerifier.Builder(apk).build().verify()
            SigVerification(
                verified = result.isVerified,
                v1Verified = result.isVerifiedUsingV1Scheme(),
                v2Verified = result.isVerifiedUsingV2Scheme(),
                v3Verified = result.isVerifiedUsingV3Scheme(),
                errors = result.errors.map { it.toString() },
            )
        }.getOrElse { e ->
            SigVerification(
                verified = false,
                v1Verified = false,
                v2Verified = false,
                v3Verified = false,
                errors = listOf(e.message ?: "Verification failed"),
            )
        }
    }
}
