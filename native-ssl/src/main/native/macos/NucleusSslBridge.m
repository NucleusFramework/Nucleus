#include <jni.h>
#import <Security/Security.h>
#import <CoreFoundation/CoreFoundation.h>

// Not exposed as a C constant in the public Security headers, but present as a
// dictionary key in trust settings. JetBrains defines it the same way (as a string literal).
#define kSecTrustSettingsPolicyName CFSTR("kSecTrustSettingsPolicyName")

/**
 * Returns true if the certificate is genuinely self-signed:
 *   1. Subject DN == Issuer DN (fast field comparison).
 *   2. Signature is valid against its own public key (cryptographic check).
 *
 * The second step uses SecTrustSetAnchorCertificates with the certificate
 * as its own anchor: if the signature is invalid SecTrustEvaluateWithError
 * returns NO.  This matches JetBrains jvm-native-trusted-roots behaviour
 * (certificate.verify(certificate.getPublicKey())).
 *
 * Apple requires self-signed status for kSecTrustSettingsResultTrustRoot.
 */
static BOOL isSelfSigned(SecCertificateRef cert) {
    // Step 1: DN field comparison (cheap).
    CFDataRef subject = SecCertificateCopyNormalizedSubjectSequence(cert);
    CFDataRef issuer  = SecCertificateCopyNormalizedIssuerSequence(cert);
    BOOL dnEqual = (subject && issuer) ? CFEqual(subject, issuer) : NO;
    if (subject) CFRelease(subject);
    if (issuer)  CFRelease(issuer);
    if (!dnEqual) return NO;

    // Step 2: Cryptographic signature verification.
    // Set the certificate itself as the only trust anchor; evaluation succeeds
    // only if the certificate was genuinely signed by its own private key.
    const void *vals[] = { cert };
    CFArrayRef certArray   = CFArrayCreate(kCFAllocatorDefault, vals, 1, &kCFTypeArrayCallBacks);
    CFArrayRef anchorArray = CFArrayCreate(kCFAllocatorDefault, vals, 1, &kCFTypeArrayCallBacks);
    if (!certArray || !anchorArray) {
        if (certArray)   CFRelease(certArray);
        if (anchorArray) CFRelease(anchorArray);
        return NO;
    }

    SecPolicyRef policy = SecPolicyCreateBasicX509();
    SecTrustRef trust = NULL;
    BOOL valid = NO;

    if (policy && SecTrustCreateWithCertificates(certArray, policy, &trust) == errSecSuccess && trust) {
        SecTrustSetAnchorCertificates(trust, anchorArray);
        if (@available(macOS 10.14, *)) {
            CFErrorRef error = NULL;
            valid = SecTrustEvaluateWithError(trust, &error);
            if (error) CFRelease(error);
        } else {
            SecTrustResultType result = kSecTrustResultInvalid;
            if (SecTrustEvaluate(trust, &result) == errSecSuccess) {
                valid = (result == kSecTrustResultUnspecified ||
                         result == kSecTrustResultProceed);
            }
        }
    }

    if (trust)  CFRelease(trust);
    if (policy) CFRelease(policy);
    CFRelease(certArray);
    CFRelease(anchorArray);
    return valid;
}

/**
 * Validates a certificate against the system trust store via SecTrustEvaluateWithError.
 *
 * server=false avoids Apple's strict server-side policies (e.g. max 2-year validity for
 * user-added CAs) which would incorrectly reject legitimate root CAs.
 * See:
 *   https://developer.apple.com/documentation/security/2980705-sectrustevaluatewitherror
 *   https://discussions.apple.com/thread/254684451
 */
static BOOL validateCertificate(SecCertificateRef cert) {
    const void *certValues[] = { cert };
    CFArrayRef certArray = CFArrayCreate(kCFAllocatorDefault, certValues, 1, &kCFTypeArrayCallBacks);
    if (!certArray) return NO;

    SecPolicyRef policy = SecPolicyCreateSSL(false, NULL);
    if (!policy) { CFRelease(certArray); return NO; }

    SecTrustRef trust = NULL;
    OSStatus status = SecTrustCreateWithCertificates(certArray, policy, &trust);
    CFRelease(certArray);
    CFRelease(policy);

    if (status != errSecSuccess || !trust) {
        if (trust) CFRelease(trust);
        return NO;
    }

    BOOL trusted = NO;
    if (@available(macOS 10.14, *)) {
        CFErrorRef error = NULL;
        trusted = SecTrustEvaluateWithError(trust, &error);
        if (error) CFRelease(error);
    } else {
        // Fallback for macOS 10.13: SecTrustEvaluate is deprecated but functional.
        SecTrustResultType result = kSecTrustResultInvalid;
        if (SecTrustEvaluate(trust, &result) == errSecSuccess) {
            trusted = (result == kSecTrustResultUnspecified ||
                       result == kSecTrustResultProceed);
        }
    }
    CFRelease(trust);
    return trusted;
}

/**
 * Determines if a certificate in the user/admin domain is a trusted root.
 *
 * Mirrors JetBrains jvm-native-trusted-roots SecurityFrameworkUtil.isTrustedRoot logic:
 *
 *  1. Try user-domain trust settings first, then admin-domain.
 *  2. No settings found in either domain → fall back to SecTrustEvaluateWithError.
 *  3. Empty trust settings array → always trusted (per Apple docs).
 *  4. For each usage constraints dictionary:
 *     - kSecTrustSettingsResult must be TrustRoot (default if key absent).
 *       Only self-signed certificates may carry TrustRoot.
 *     - kSecTrustSettingsAllowedError: acknowledged, not evaluated.
 *     - kSecTrustSettingsPolicyName: acknowledged, not evaluated.
 *     - kSecTrustSettingsPolicy: if present, OID must be kSecPolicyAppleSSL.
 *     - Accept only when every key in the dictionary is accounted for.
 *       Unknown constraints might express stricter rules we cannot evaluate.
 *
 * References:
 *   https://developer.apple.com/documentation/security/1400261-sectrustsettingscopytrustsetting
 *   https://chromium.googlesource.com/chromium/src/+/main/net/cert/internal/trust_store_mac.cc
 */
static BOOL isTrustedRoot(SecCertificateRef cert) {
    // Try user domain first; fall back to admin if no settings exist in user domain.
    CFArrayRef trustSettings = NULL;
    OSStatus status = SecTrustSettingsCopyTrustSettings(
        cert, kSecTrustSettingsDomainUser, &trustSettings);

    if (status == errSecItemNotFound || status == errSecNoTrustSettings) {
        if (trustSettings) { CFRelease(trustSettings); trustSettings = NULL; }
        status = SecTrustSettingsCopyTrustSettings(
            cert, kSecTrustSettingsDomainAdmin, &trustSettings);
    }

    // No trust settings in any user/admin domain → live evaluation fallback.
    if (status == errSecItemNotFound || status == errSecNoTrustSettings) {
        if (trustSettings) CFRelease(trustSettings);
        return validateCertificate(cert);
    }

    if (status != errSecSuccess || trustSettings == NULL) {
        if (trustSettings) CFRelease(trustSettings);
        return NO;
    }

    // Empty trust settings array → "always trust this certificate" (Apple docs).
    CFIndex constraintCount = CFArrayGetCount(trustSettings);
    if (constraintCount == 0) {
        CFRelease(trustSettings);
        return YES;
    }

    BOOL selfSigned = isSelfSigned(cert);
    BOOL trusted = NO;

    for (CFIndex i = 0; i < constraintCount && !trusted; i++) {
        CFDictionaryRef constraint =
            (CFDictionaryRef)CFArrayGetValueAtIndex(trustSettings, i);
        CFIndex totalKeys = CFDictionaryGetCount(constraint);
        CFIndex processed = 0;

        // kSecTrustSettingsResult
        // Default is kSecTrustSettingsResultTrustRoot when the key is absent.
        SInt32 tsResult = kSecTrustSettingsResultTrustRoot;
        CFNumberRef resultNum =
            (CFNumberRef)CFDictionaryGetValue(constraint, kSecTrustSettingsResult);
        if (resultNum) {
            CFNumberGetValue(resultNum, kCFNumberSInt32Type, &tsResult);
            processed++;
        }
        // Only TrustRoot is accepted; TrustAsRoot, Deny, and Unspecified are skipped.
        if (tsResult != kSecTrustSettingsResultTrustRoot) continue;
        // Apple: TrustRoot is only valid for self-signed certificates.
        if (!selfSigned) continue;

        // kSecTrustSettingsAllowedError: acknowledged, not evaluated (matches JetBrains).
        if (CFDictionaryGetValue(constraint, kSecTrustSettingsAllowedError) != NULL) processed++;

        // kSecTrustSettingsPolicyName: acknowledged, not evaluated (matches JetBrains).
        if (CFDictionaryGetValue(constraint, kSecTrustSettingsPolicyName) != NULL) processed++;

        // kSecTrustSettingsPolicy: if present, the policy OID must be kSecPolicyAppleSSL.
        CFTypeRef policyRef = CFDictionaryGetValue(constraint, kSecTrustSettingsPolicy);
        if (policyRef) {
            processed++;
            CFDictionaryRef props = SecPolicyCopyProperties((SecPolicyRef)policyRef);
            if (!props) continue;
            CFTypeRef oidRef = CFDictionaryGetValue(props, kSecPolicyOid);
            BOOL isSSL = (oidRef != NULL && CFEqual(oidRef, kSecPolicyAppleSSL));
            CFRelease(props);
            if (!isSSL) continue;
        }

        // Return true only when every constraint key is accounted for.
        if (totalKeys == processed) {
            trusted = YES;
        }
    }

    CFRelease(trustSettings);
    return trusted;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_nativessl_mac_NativeSslBridge_nativeGetSystemCertificates(
    JNIEnv *env, jclass clazz) {

    CFMutableArrayRef allDer =
        CFArrayCreateMutable(kCFAllocatorDefault, 0, &kCFTypeArrayCallBacks);
    if (!allDer) return NULL;

    // 1. System root CAs shipped with macOS – implicitly trusted.
    CFArrayRef anchors = NULL;
    OSStatus status = SecTrustCopyAnchorCertificates(&anchors);
    if (status == errSecSuccess && anchors != NULL) {
        CFIndex count = CFArrayGetCount(anchors);
        for (CFIndex i = 0; i < count; i++) {
            SecCertificateRef cert =
                (SecCertificateRef)CFArrayGetValueAtIndex(anchors, i);
            CFDataRef derData = SecCertificateCopyData(cert);
            if (derData != NULL) {
                CFArrayAppendValue(allDer, derData);
                CFRelease(derData);
            }
        }
        CFRelease(anchors);
    }

    // 2. User and admin domain: enumerate certs with trust settings and evaluate.
    SecTrustSettingsDomain domains[] = {
        kSecTrustSettingsDomainUser,
        kSecTrustSettingsDomainAdmin,
    };
    for (int d = 0; d < 2; d++) {
        CFArrayRef certs = NULL;
        status = SecTrustSettingsCopyCertificates(domains[d], &certs);
        if (status != errSecSuccess || certs == NULL) {
            if (certs) CFRelease(certs);
            continue;
        }
        CFIndex count = CFArrayGetCount(certs);
        for (CFIndex i = 0; i < count; i++) {
            SecCertificateRef cert =
                (SecCertificateRef)CFArrayGetValueAtIndex(certs, i);
            if (!isTrustedRoot(cert)) continue;
            CFDataRef derData = SecCertificateCopyData(cert);
            if (derData != NULL) {
                CFArrayAppendValue(allDer, derData);
                CFRelease(derData);
            }
        }
        CFRelease(certs);
    }

    // Build Java byte[][] from the collected DER blobs.
    CFIndex total = CFArrayGetCount(allDer);
    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    jobjectArray result =
        (*env)->NewObjectArray(env, (jsize)total, byteArrayClass, NULL);
    if (result == NULL) {
        CFRelease(allDer);
        return NULL; // OOM – JVM will throw
    }

    for (CFIndex i = 0; i < total; i++) {
        CFDataRef derData = (CFDataRef)CFArrayGetValueAtIndex(allDer, i);
        CFIndex len = CFDataGetLength(derData);
        jbyteArray jBytes = (*env)->NewByteArray(env, (jsize)len);
        if (jBytes == NULL) {
            CFRelease(allDer);
            return NULL;
        }
        (*env)->SetByteArrayRegion(env, jBytes, 0, (jsize)len,
                                   (const jbyte *)CFDataGetBytePtr(derData));
        (*env)->SetObjectArrayElement(env, result, (jsize)i, jBytes);
        (*env)->DeleteLocalRef(env, jBytes);
    }

    CFRelease(allDer);
    return result;
}
