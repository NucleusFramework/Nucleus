#include <jni.h>
#include <windows.h>
#include <wincrypt.h>

/**
 * Windows JNI bridge for system certificate retrieval.
 *
 * Scans 5 store locations x 2 stores (ROOT + CA) to collect DER-encoded
 * certificates, including those deployed via Group Policy, Active Directory,
 * and Enterprise stores that SunMSCAPI does not reach.
 *
 * For ROOT stores, all certificates are included directly.
 * For CA stores, each certificate is validated via CertGetCertificateChain +
 * CertVerifyCertificateChainPolicy (CERT_CHAIN_POLICY_BASE) with cache-only
 * revocation checking (no network).
 *
 * Deduplication is performed via SHA-1 thumbprint (CERT_HASH_PROP_ID),
 * the standard Windows certificate identity used by CryptoAPI.
 *
 * Built with /NODEFAULTLIB – no CRT dependency, uses Win32 heap APIs only.
 */

/* ── CRT-free: provide memcpy/memset/memcmp so the linker resolves them ── */
#pragma function(memcpy, memset, memcmp)

void *memcpy(void *dst, const void *src, size_t n) {
    BYTE *d = (BYTE *)dst;
    const BYTE *s = (const BYTE *)src;
    while (n--) *d++ = *s++;
    return dst;
}

void *memset(void *dst, int val, size_t n) {
    BYTE *d = (BYTE *)dst;
    while (n--) *d++ = (BYTE)val;
    return dst;
}

int memcmp(const void *a, const void *b, size_t n) {
    const BYTE *pa = (const BYTE *)a;
    const BYTE *pb = (const BYTE *)b;
    while (n--) {
        if (*pa != *pb) return (int)*pa - (int)*pb;
        pa++; pb++;
    }
    return 0;
}

#define NUM_LOCATIONS 5
#define NUM_STORES    2
#define MAX_CERTS     4096
#define THUMB_LEN     20  /* SHA-1 = 20 bytes */

static const DWORD STORE_LOCATIONS[NUM_LOCATIONS] = {
    CERT_SYSTEM_STORE_CURRENT_USER,                  /* 0x00010000 */
    CERT_SYSTEM_STORE_LOCAL_MACHINE,                  /* 0x00020000 */
    CERT_SYSTEM_STORE_CURRENT_USER_GROUP_POLICY,      /* 0x00070000 */
    CERT_SYSTEM_STORE_LOCAL_MACHINE_GROUP_POLICY,      /* 0x00080000 */
    CERT_SYSTEM_STORE_LOCAL_MACHINE_ENTERPRISE         /* 0x00090000 */
};

static const LPCWSTR STORE_NAMES[NUM_STORES] = {
    L"ROOT",
    L"CA"
};

/* ── Heap helpers ── */

static void *heap_alloc(SIZE_T size) {
    return HeapAlloc(GetProcessHeap(), 0, size);
}

static void *heap_alloc_zero(SIZE_T size) {
    return HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, size);
}

static void heap_free(void *ptr) {
    if (ptr) HeapFree(GetProcessHeap(), 0, ptr);
}

/* ── Collected cert entry ── */

typedef struct {
    BYTE  *derData;
    DWORD  derLen;
    BYTE   thumb[THUMB_LEN];
} CertEntry;

/**
 * Validates an intermediate (CA) certificate by building a chain and
 * verifying it against CERT_CHAIN_POLICY_BASE.
 * Uses CERT_CHAIN_REVOCATION_CHECK_CACHE_ONLY to avoid network calls.
 */
static BOOL validateCaCertificate(PCCERT_CONTEXT pCert) {
    CERT_CHAIN_PARA chainPara;
    PCCERT_CHAIN_CONTEXT pChainContext = NULL;
    BOOL valid = FALSE;

    memset(&chainPara, 0, sizeof(chainPara));
    chainPara.cbSize = sizeof(chainPara);

    if (!CertGetCertificateChain(
            NULL, pCert, NULL, NULL, &chainPara,
            CERT_CHAIN_REVOCATION_CHECK_CACHE_ONLY, NULL, &pChainContext)) {
        return FALSE;
    }

    CERT_CHAIN_POLICY_PARA policyPara;
    CERT_CHAIN_POLICY_STATUS policyStatus;

    memset(&policyPara, 0, sizeof(policyPara));
    policyPara.cbSize = sizeof(policyPara);

    memset(&policyStatus, 0, sizeof(policyStatus));
    policyStatus.cbSize = sizeof(policyStatus);

    if (CertVerifyCertificateChainPolicy(
            CERT_CHAIN_POLICY_BASE, pChainContext, &policyPara, &policyStatus)) {
        valid = (policyStatus.dwError == 0);
    }

    CertFreeCertificateChain(pChainContext);
    return valid;
}

/**
 * Returns TRUE if the thumbprint already exists in the entries.
 */
static BOOL isDuplicate(const CertEntry *entries, int count, const BYTE *thumb) {
    for (int i = 0; i < count; i++) {
        if (memcmp(entries[i].thumb, thumb, THUMB_LEN) == 0) return TRUE;
    }
    return FALSE;
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpReserved) {
    (void)hinstDLL;
    (void)fdwReason;
    (void)lpReserved;
    return TRUE;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_nativessl_windows_WindowsSslBridge_nativeGetSystemCertificates(
    JNIEnv *env, jclass clazz) {

    (void)clazz;

    /* All storage on heap to avoid __chkstk for large stack frames */
    CertEntry *entries = (CertEntry *)heap_alloc_zero(MAX_CERTS * sizeof(CertEntry));
    if (entries == NULL) return NULL;

    int certCount = 0;

    for (int loc = 0; loc < NUM_LOCATIONS; loc++) {
        for (int st = 0; st < NUM_STORES; st++) {
            HCERTSTORE hStore = CertOpenStore(
                CERT_STORE_PROV_SYSTEM_W, 0, (HCRYPTPROV_LEGACY)0,
                STORE_LOCATIONS[loc] | CERT_STORE_OPEN_EXISTING_FLAG | CERT_STORE_READONLY_FLAG,
                STORE_NAMES[st]);

            if (hStore == NULL) continue;

            BOOL isRootStore = (st == 0);

            PCCERT_CONTEXT pCert = NULL;
            while ((pCert = CertEnumCertificatesInStore(hStore, pCert)) != NULL) {
                if (certCount >= MAX_CERTS) break;

                if (!isRootStore && !validateCaCertificate(pCert)) continue;

                BYTE thumb[THUMB_LEN];
                DWORD thumbSize = THUMB_LEN;
                if (!CertGetCertificateContextProperty(
                        pCert, CERT_HASH_PROP_ID, thumb, &thumbSize)) {
                    continue;
                }

                if (isDuplicate(entries, certCount, thumb)) continue;

                DWORD cbEncoded = pCert->cbCertEncoded;
                BYTE *copy = (BYTE *)heap_alloc(cbEncoded);
                if (copy == NULL) continue;
                memcpy(copy, pCert->pbCertEncoded, cbEncoded);

                entries[certCount].derData = copy;
                entries[certCount].derLen = cbEncoded;
                memcpy(entries[certCount].thumb, thumb, THUMB_LEN);
                certCount++;
            }

            CertCloseStore(hStore, 0);
        }
    }

    /* Build Java byte[][] */
    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    if (byteArrayClass == NULL) {
        for (int i = 0; i < certCount; i++) heap_free(entries[i].derData);
        heap_free(entries);
        return NULL;
    }

    jobjectArray result = (*env)->NewObjectArray(env, (jsize)certCount, byteArrayClass, NULL);
    if (result == NULL) {
        for (int i = 0; i < certCount; i++) heap_free(entries[i].derData);
        heap_free(entries);
        return NULL;
    }

    for (int i = 0; i < certCount; i++) {
        jbyteArray jBytes = (*env)->NewByteArray(env, (jsize)entries[i].derLen);
        if (jBytes == NULL) {
            for (int j = i; j < certCount; j++) heap_free(entries[j].derData);
            heap_free(entries);
            return NULL;
        }
        (*env)->SetByteArrayRegion(env, jBytes, 0, (jsize)entries[i].derLen,
                                   (const jbyte *)entries[i].derData);
        (*env)->SetObjectArrayElement(env, result, (jsize)i, jBytes);
        (*env)->DeleteLocalRef(env, jBytes);
        heap_free(entries[i].derData);
    }

    heap_free(entries);
    return result;
}
