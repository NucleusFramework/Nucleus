// Network connectivity and metered status detection on Windows.
// Uses COM INetworkListManager for connectivity and INetworkCostManager for metered status.

#include "nucleus_system_info_common.h"
#include <objbase.h>
#include <netlistmgr.h>

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeIsNetworkConnected(
    JNIEnv *env, jclass clazz) {

    HRESULT hrInit = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hrInit) && hrInit != S_FALSE && hrInit != RPC_E_CHANGED_MODE)
        return JNI_FALSE;

    INetworkListManager *pNLM = NULL;
    HRESULT hr = CoCreateInstance(&CLSID_NetworkListManager, NULL, CLSCTX_ALL,
        &IID_INetworkListManager, (void **)&pNLM);

    if (FAILED(hr) || !pNLM) {
        if (SUCCEEDED(hrInit) || hrInit == S_FALSE) CoUninitialize();
        return JNI_FALSE;
    }

    NLM_CONNECTIVITY connectivity = 0;
    hr = pNLM->lpVtbl->GetConnectivity(pNLM, &connectivity);
    pNLM->lpVtbl->Release(pNLM);

    if (SUCCEEDED(hrInit) || hrInit == S_FALSE) CoUninitialize();

    if (FAILED(hr)) return JNI_FALSE;

    return (connectivity &
        (NLM_CONNECTIVITY_IPV4_INTERNET | NLM_CONNECTIVITY_IPV6_INTERNET))
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGetMeteredStatus(
    JNIEnv *env, jclass clazz) {

    HRESULT hrInit = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hrInit) && hrInit != S_FALSE && hrInit != RPC_E_CHANGED_MODE)
        return 0; // UNKNOWN

    INetworkCostManager *pCostMgr = NULL;
    HRESULT hr = CoCreateInstance(&CLSID_NetworkListManager, NULL, CLSCTX_ALL,
        &IID_INetworkCostManager, (void **)&pCostMgr);

    if (FAILED(hr) || !pCostMgr) {
        if (SUCCEEDED(hrInit) || hrInit == S_FALSE) CoUninitialize();
        return 0; // UNKNOWN
    }

    DWORD cost = 0;
    hr = pCostMgr->lpVtbl->GetCost(pCostMgr, &cost, NULL);
    pCostMgr->lpVtbl->Release(pCostMgr);

    if (SUCCEEDED(hrInit) || hrInit == S_FALSE) CoUninitialize();

    if (FAILED(hr)) return 0; // UNKNOWN

    if (cost & (NLM_CONNECTION_COST_FIXED | NLM_CONNECTION_COST_VARIABLE |
                NLM_CONNECTION_COST_OVERDATALIMIT | NLM_CONNECTION_COST_ROAMING))
        return 2; // METERED

    if (cost & NLM_CONNECTION_COST_UNRESTRICTED)
        return 1; // UNMETERED

    return 0; // UNKNOWN
}
