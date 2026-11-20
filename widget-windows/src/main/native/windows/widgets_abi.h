/**
 * widgets_abi.h
 *
 * Hand-written ABI declarations for the Windows App SDK
 * `Microsoft.Windows.Widgets.Providers` namespace (WidgetContract 12).
 *
 * Generated from Microsoft.Windows.Widgets.winmd (NuGet
 * Microsoft.WindowsAppSDK.Widgets 2.6.5) with `winmdidl`, then transcribed:
 * the vtable order and the IIDs below MUST match the IDL exactly. The SDK
 * ships no C/C++ ABI header for this namespace, and pulling the NuGet package
 * into the native build just for these few interfaces is not worth it.
 */

#pragma once

#include <inspectable.h>
#include <windows.foundation.h>
#include <windows.foundation.collections.h>
#include <windows.storage.streams.h>

// IReference<bool> / IReference<int> are only specialized by unrelated SDK
// headers; declare them with the SDK's own guards so either copy wins.
#ifndef DEF___FIReference_1_boolean_USE
#define DEF___FIReference_1_boolean_USE
namespace ABI { namespace Windows { namespace Foundation {
template <>
struct __declspec(uuid("3c00fd60-2950-5939-a21a-2d12c5a01b8a"))
IReference<bool> : IReference_impl<ABI::Windows::Foundation::Internal::AggregateType<bool, boolean>> {
    static const wchar_t *z_get_rc_name_impl() { return L"Windows.Foundation.IReference`1<Boolean>"; }
};
}}}
#endif

#ifndef DEF___FIReference_1_int_USE
#define DEF___FIReference_1_int_USE
namespace ABI { namespace Windows { namespace Foundation {
template <>
struct __declspec(uuid("548cefbd-bc8a-5fa0-8df2-957440fc8bf4"))
IReference<int> : IReference_impl<int> {
    static const wchar_t *z_get_rc_name_impl() { return L"Windows.Foundation.IReference`1<Int32>"; }
};
}}}
#endif

namespace ABI { namespace Microsoft { namespace Windows { namespace Widgets {

enum WidgetSize : int {
    WidgetSize_Small = 0,
    WidgetSize_Medium = 1,
    WidgetSize_Large = 2,
};

namespace Providers {

using ::ABI::Windows::Foundation::DateTime;
using ::ABI::Windows::Foundation::IDeferral;
using ::ABI::Windows::Foundation::IReference;
using ::ABI::Windows::Foundation::Collections::IMap;
using ::ABI::Windows::Storage::Streams::IRandomAccessStreamReference;

// Runtime classes are passed as their [default] interface on the ABI.
struct IWidgetContext;
struct IWidgetInfo;
struct IWidgetUpdateRequestOptions;
struct IWidgetResourceRequest;
struct IWidgetResourceResponse;

MIDL_INTERFACE("903C518B-40BC-5BC6-88F7-AF9D81C0CDC1")
IWidgetContext : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Id(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DefinitionId(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Size(WidgetSize *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_IsActive(boolean *value) = 0;
};

MIDL_INTERFACE("C593CC57-04B9-52CA-88AD-46FEA21EA340")
IWidgetActionInvokedArgs : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetContext(IWidgetContext **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Verb(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Data(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_CustomState(HSTRING *value) = 0;
};

MIDL_INTERFACE("1D9E5FB5-2BCE-5350-87B1-D63199526639")
IWidgetAnalyticsInfoReportedArgs : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetContext(IWidgetContext **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_AnalyticsJson(HSTRING *value) = 0;
};

MIDL_INTERFACE("2C226D54-2252-576B-A197-370B28D25C2F")
IWidgetContextChangedArgs : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetContext(IWidgetContext **value) = 0;
};

MIDL_INTERFACE("41DEA311-DD9B-5B8B-B493-3A30552116B8")
IWidgetCustomizationRequestedArgs : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetContext(IWidgetContext **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_CustomState(HSTRING *value) = 0;
};

MIDL_INTERFACE("30EFA627-B21F-55D5-B91A-B23B4AA13645")
IWidgetErrorInfoReportedArgs : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetContext(IWidgetContext **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ErrorJson(HSTRING *value) = 0;
};

MIDL_INTERFACE("CEA11F42-A020-5DB5-89E2-B7DECE4AE5CB")
IWidgetInfo : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetContext(IWidgetContext **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Template(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Data(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_CustomState(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_LastUpdateTime(DateTime *value) = 0;
};

// WidgetContract 9
MIDL_INTERFACE("081B0A6F-D784-5408-BB29-252FEF2926D4")
IWidgetInfo2 : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_IsPlaceholderContent(boolean *value) = 0;
};

// WidgetContract 11
MIDL_INTERFACE("965538CD-289D-54AB-916E-9315EBF97EA4")
IWidgetInfo3 : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Rank(INT32 *value) = 0;
};

MIDL_INTERFACE("71CB10C0-671E-48E3-B995-207940397123")
IWidgetManager : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE UpdateWidget(IWidgetUpdateRequestOptions *options) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetWidgetIds(UINT32 *resultSize, HSTRING **result) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetWidgetInfo(HSTRING widgetId, IWidgetInfo **result) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetWidgetInfos(UINT32 *resultSize, IWidgetInfo ***result) = 0;
    virtual HRESULT STDMETHODCALLTYPE DeleteWidget(HSTRING widgetId) = 0;
};

// WidgetContract 6
MIDL_INTERFACE("55C65A27-8845-406C-9EE1-1E79F0556BEF")
IWidgetManager2 : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE SendMessageToContent(HSTRING widgetId, HSTRING message) = 0;
};

MIDL_INTERFACE("7F233B06-28E5-5E2B-8C04-A4FA747C28C7")
IWidgetManagerStatics : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE GetDefault(IWidgetManager **result) = 0;
};

MIDL_INTERFACE("2261CB2B-C741-5F96-9ADB-FB3A7667BCB6")
IWidgetMessageReceivedArgs : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetContext(IWidgetContext **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Message(HSTRING *value) = 0;
};

MIDL_INTERFACE("113D249F-82D9-57CB-8CEA-9A5291F2FE22")
IWidgetResourceRequest : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Uri(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Method(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Method(HSTRING value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Content(IRandomAccessStreamReference **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Content(IRandomAccessStreamReference *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Headers(IMap<HSTRING, HSTRING> **value) = 0;
};

MIDL_INTERFACE("2BB30F4D-0166-58E3-AAF6-31B2AE970BCD")
IWidgetResourceRequestedArgs : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetContext(IWidgetContext **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Request(IWidgetResourceRequest **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Response(IWidgetResourceResponse **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Response(IWidgetResourceResponse *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDeferral(IDeferral **result) = 0;
};

MIDL_INTERFACE("03A2D32C-2E9E-54A3-B084-1479D5060F80")
IWidgetResourceResponse : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Content(IRandomAccessStreamReference **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Headers(IMap<HSTRING, HSTRING> **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ReasonPhrase(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_StatusCode(INT32 *value) = 0;
};

MIDL_INTERFACE("08881EF1-A78A-5804-B070-9153A8657F85")
IWidgetResourceResponseFactory : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE CreateInstance(
        IRandomAccessStreamReference *content, HSTRING reasonPhrase, INT32 statusCode,
        IWidgetResourceResponse **value) = 0;
};

MIDL_INTERFACE("B09CA8F7-7424-5687-BAAF-7DD6FA639672")
IWidgetUpdateRequestOptions : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_WidgetId(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Template(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Template(HSTRING value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Data(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Data(HSTRING value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_CustomState(HSTRING *value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_CustomState(HSTRING value) = 0;
};

// WidgetContract 9
MIDL_INTERFACE("77C4EFC4-38F3-57A5-ABA1-F83F257B899E")
IWidgetUpdateRequestOptions2 : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_IsPlaceholderContent(IReference<bool> **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_IsPlaceholderContent(IReference<bool> *value) = 0;
};

// WidgetContract 11
MIDL_INTERFACE("A78E2A8B-A26C-596A-ADE3-DB8F4C72FE02")
IWidgetUpdateRequestOptions3 : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Rank(IReference<int> **value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Rank(IReference<int> *value) = 0;
};

MIDL_INTERFACE("E0E00AF8-1D10-57A8-9419-3F568E854DAA")
IWidgetUpdateRequestOptionsFactory : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE CreateInstance(HSTRING widgetId, IWidgetUpdateRequestOptions **value) = 0;
};

MIDL_INTERFACE("4645B5E3-D332-5D11-82F0-3607E5DF6018")
IWidgetUpdateRequestOptionsStatics : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_UnsetValue(HSTRING *value) = 0;
};

// ---- Interfaces implemented by the provider (us) ----

MIDL_INTERFACE("5C5774CC-72A0-452D-B9ED-075C0DD25EED")
IWidgetProvider : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE CreateWidget(IWidgetContext *widgetContext) = 0;
    virtual HRESULT STDMETHODCALLTYPE DeleteWidget(HSTRING widgetId, HSTRING customState) = 0;
    virtual HRESULT STDMETHODCALLTYPE OnActionInvoked(IWidgetActionInvokedArgs *args) = 0;
    virtual HRESULT STDMETHODCALLTYPE OnWidgetContextChanged(IWidgetContextChangedArgs *args) = 0;
    virtual HRESULT STDMETHODCALLTYPE Activate(IWidgetContext *widgetContext) = 0;
    virtual HRESULT STDMETHODCALLTYPE Deactivate(HSTRING widgetId) = 0;
};

// WidgetContract 2
MIDL_INTERFACE("38C3A963-DD93-479D-9276-04BF84EE1816")
IWidgetProvider2 : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE OnCustomizationRequested(IWidgetCustomizationRequestedArgs *args) = 0;
};

// WidgetContract 2
MIDL_INTERFACE("661985A5-D187-482D-9EEF-6FDA05D21845")
IWidgetProviderAnalytics : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE OnAnalyticsInfoReported(IWidgetAnalyticsInfoReportedArgs *args) = 0;
};

// WidgetContract 2
MIDL_INTERFACE("90C1B5F0-0D3A-4AC6-ABB7-C97B367B8FCC")
IWidgetProviderErrors : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE OnErrorInfoReported(IWidgetErrorInfoReportedArgs *args) = 0;
};

// WidgetContract 6
MIDL_INTERFACE("EA4DC186-9E24-4B35-A5EF-A9F5DF72D6AC")
IWidgetProviderMessage : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE OnMessageReceived(IWidgetMessageReceivedArgs *args) = 0;
};

// WidgetContract 6
MIDL_INTERFACE("DCF328C0-012C-40F5-BB28-3A1C714D027D")
IWidgetResourceProvider : public IInspectable {
    virtual HRESULT STDMETHODCALLTYPE OnResourceRequested(IWidgetResourceRequestedArgs *args) = 0;
};

} // namespace Providers
}}}} // namespace ABI::Microsoft::Windows::Widgets

#define RuntimeClass_WidgetManager L"Microsoft.Windows.Widgets.Providers.WidgetManager"
#define RuntimeClass_WidgetUpdateRequestOptions L"Microsoft.Windows.Widgets.Providers.WidgetUpdateRequestOptions"
#define RuntimeClass_WidgetResourceResponse L"Microsoft.Windows.Widgets.Providers.WidgetResourceResponse"
