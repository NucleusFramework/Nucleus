//! Microsoft Store backend over `Windows.Services.Store.StoreContext`.
//!
//! Derived from tauri-plugin-iap's `src/windows.rs`
//! (https://github.com/Choochmeque/tauri-plugin-iap), Copyright (c) 2025 Vladimir Pankratov,
//! MIT License. Decoupled from Tauri, and reworked where the original guessed: prices come
//! from `Unformatted*Price` with a locale-aware fallback (it read `"4,99 €"` as 499), no
//! purchase date is invented (it assumed 30 days before expiry), a "never expires" sentinel is
//! not reported as a date, SKUs are bought through `StoreSku.RequestPurchaseAsync` instead of a
//! hand-written `ExtendedJsonData`, every blocking call is cancellable, and consumable
//! fulfilment carries a tracking id minted once per purchase so a retried `consume` is
//! idempotent.

use std::collections::HashSet;
use std::sync::{Mutex, MutexGuard, OnceLock};

use serde_json::{json, Value};
use windows::Foundation::TypedEventHandler;
use windows::Services::Store::{
    StoreAppLicense, StoreConsumableStatus, StoreContext, StoreLicense, StorePrice, StoreProduct,
    StorePurchaseResult, StorePurchaseStatus, StoreSku,
};
use windows::Win32::Foundation::{
    APPMODEL_ERROR_NO_PACKAGE, ERROR_INSUFFICIENT_BUFFER, HWND, LPARAM, WIN32_ERROR,
};
use windows::Win32::Storage::Packaging::Appx::GetCurrentPackageFullName;
use windows::Win32::System::Com::{
    CoGetApartmentType, CoIncrementMTAUsage, APTTYPE, APTTYPEQUALIFIER,
};
use windows::Win32::System::WinRT::{RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED};
use windows::Win32::UI::Shell::IInitializeWithWindow;
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetAncestor, GetForegroundWindow, GetWindowThreadProcessId, IsWindow,
    IsWindowVisible, GA_ROOTOWNER,
};
use windows_collections::IIterable;
use windows_core::{AgileReference, Interface, BOOL, GUID, HRESULT, HSTRING};
use windows_future::{IAsyncInfo, IAsyncOperation};

use crate::protocol::{
    normalize_price, period_unit, store_id_from_sku_store_id, store_ticks_to_unix_millis, to_value,
    trial_time_left, AppLicenseDto, EntitlementDto, OfferDto, PeriodDto, ProductDto, PurchaseDto,
    StoreError, StoreResult, SubscriptionDto, ENTITLEMENT_ACTIVE, ENTITLEMENT_EXPIRED,
    ENTITLEMENT_NOT_OWNED, PRODUCT_AUTO_RENEWABLE, PRODUCT_CONSUMABLE, PRODUCT_NON_CONSUMABLE,
    PRODUCT_UNKNOWN, STATE_EXPIRED, STATE_PURCHASED,
};
use crate::requests::{cancelled_error, Request};
use crate::Sink;

const E_ABORT: i32 = 0x8000_4004_u32 as i32;
const HRESULT_ERROR_CANCELLED: i32 = 0x8007_04C7_u32 as i32;
const HRESULT_INVALID_WINDOW_HANDLE: i32 = 0x8007_0578_u32 as i32;
const E_ILLEGAL_METHOD_CALL: i32 = 0x8000_000E_u32 as i32;
/// The Store's answer to an app it has no listing for: unpackaged, sideloaded, or not
/// published (observed on every Store call from such a process).
const IAP_E_NOT_ASSOCIATED: i32 = 0x803F_6107_u32 as i32;

const ALL_KINDS: [&str; 4] = [
    "Consumable",
    "UnmanagedConsumable",
    "Durable",
    "Subscription",
];
const CONSUMABLE_KINDS: [&str; 2] = ["Consumable", "UnmanagedConsumable"];

/// The `StoreContext` behind `OfflineLicensesChanged`, kept alive for the life of the process.
struct Listener {
    _store: StoreContext,
    _token: i64,
    /// The apartment the subscription was made in; read by tests.
    #[cfg_attr(not(test), allow(dead_code))]
    apartment: i32,
}

fn listener() -> MutexGuard<'static, Option<Listener>> {
    static LISTENER: OnceLock<Mutex<Option<Listener>>> = OnceLock::new();
    LISTENER
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Joins the calling thread to the MTA for the life of the returned guard.
///
/// Every request runs on a thread of its own that exits afterwards. Without a process-wide MTA
/// reference, the last such thread leaving tore the apartment down under the Store's cached
/// proxies, and the next Store call on a new thread crashed the JVM (0xC0000005, no hs_err):
/// `CoIncrementMTAUsage` keeps the MTA alive between requests.
#[must_use]
fn init_thread() -> ComScope {
    static MTA: OnceLock<()> = OnceLock::new();
    MTA.get_or_init(|| {
        // The cookie is deliberately never released: the MTA must outlive every request.
        let _ = unsafe { CoIncrementMTAUsage() };
    });
    // S_FALSE: already initialized, still to be balanced. RPC_E_CHANGED_MODE (a JVM thread in
    // an STA): not ours to uninitialize.
    let initialized = unsafe { RoInitialize(RO_INIT_MULTITHREADED) }.is_ok();
    ComScope(initialized)
}

struct ComScope(bool);

impl Drop for ComScope {
    fn drop(&mut self) {
        if self.0 {
            unsafe { RoUninitialize() };
        }
    }
}

fn winrt_error(what: &str, error: &windows_core::Error) -> StoreError {
    let hresult = error.code().0;
    let code = match hresult {
        E_ABORT | HRESULT_ERROR_CANCELLED => "cancelled",
        HRESULT_INVALID_WINDOW_HANDLE => "noOwnerWindow",
        IAP_E_NOT_ASSOCIATED => "notAssociated",
        // WININET_E_* / HTTP_E_* / the Store's own "cannot reach the service" family.
        h if (h as u32 & 0xFFFF_0000) == 0x8007_0000
            && matches!(h as u32 & 0xFFFF, 12002 | 12007 | 12029 | 12030) =>
        {
            "network"
        }
        h if (h as u32 & 0xFFFF_0000) == 0x8019_0000 => "network",
        _ => "platform",
    };
    let message = error.message();
    let message = if message.is_empty() {
        format!("{what} failed (0x{:08X})", hresult as u32)
    } else {
        format!("{what} failed (0x{:08X}): {message}", hresult as u32)
    };
    StoreError::with_hresult(code, message, hresult)
}

trait WinRtContext<T> {
    fn ctx(self, what: &str) -> StoreResult<T>;
}

impl<T> WinRtContext<T> for windows_core::Result<T> {
    fn ctx(self, what: &str) -> StoreResult<T> {
        self.map_err(|e| winrt_error(what, &e))
    }
}

/// `GetDefault` hands out a new context on every call (observed), so each request gets its
/// own: a purchase parents its UI on its own window instead of whichever window came first.
fn store_context() -> StoreResult<StoreContext> {
    StoreContext::GetDefault().ctx("StoreContext.GetDefault")
}

/// Parents the Store's purchase UI on `owner`. A desktop app must do this before any call that
/// shows UI, or the call fails with `ERROR_INVALID_WINDOW_HANDLE`.
fn attach_owner(store: &StoreContext, owner: HWND) -> StoreResult<()> {
    let init: IInitializeWithWindow = store.cast().ctx("IInitializeWithWindow")?;
    unsafe { init.Initialize(owner) }.ctx("IInitializeWithWindow.Initialize")
}

/// Blocks on `operation`, cancelling it when the request is cancelled.
fn wait<T: windows_core::RuntimeType + 'static>(
    request: &Request,
    what: &str,
    operation: windows_core::Result<IAsyncOperation<T>>,
) -> StoreResult<T> {
    let operation = operation.ctx(what)?;
    let info: IAsyncInfo = operation.cast().ctx(what)?;
    // IAsyncInfo is not Send in windows-rs; WinRT async objects are agile, so an agile
    // reference resolves on the cancelling thread without marshalling.
    let info = AgileReference::new(&info).ctx(what)?;
    let armed = request.arm(Box::new(move || {
        if let Ok(info) = info.resolve() {
            let _ = info.Cancel();
        }
    }));
    let result = operation.join();
    drop(armed);
    if request.is_cancelled() {
        return Err(cancelled_error());
    }
    result.map_err(|e| {
        if e.code().0 == E_ILLEGAL_METHOD_CALL {
            // GetResults on an operation cancelled from outside our request.
            cancelled_error()
        } else {
            winrt_error(what, &e)
        }
    })
}

fn kinds(values: &[&str]) -> IIterable<HSTRING> {
    values
        .iter()
        .map(|v| HSTRING::from(*v))
        .collect::<Vec<_>>()
        .into()
}

fn packaged() -> bool {
    let mut length = 0u32;
    let status: WIN32_ERROR = unsafe { GetCurrentPackageFullName(&mut length, None) };
    status == ERROR_INSUFFICIENT_BUFFER || (status.0 == 0 && length > 0)
}

fn not_packaged_error() -> StoreError {
    StoreError::with_hresult(
        "notPackaged",
        "The Microsoft Store needs package identity: run the app from an MSIX/AppX install",
        HRESULT::from_win32(APPMODEL_ERROR_NO_PACKAGE.0).0,
    )
}

// -------------------------------------------------------------------------------------------
// Owner window
// -------------------------------------------------------------------------------------------

fn window_of_this_process(hwnd: HWND) -> bool {
    let mut pid = 0u32;
    unsafe { GetWindowThreadProcessId(hwnd, Some(&mut pid)) };
    pid == std::process::id()
}

fn usable_owner(hwnd: HWND) -> bool {
    !hwnd.0.is_null()
        && unsafe { IsWindow(Some(hwnd)).as_bool() && IsWindowVisible(hwnd).as_bool() }
        && window_of_this_process(hwnd)
}

unsafe extern "system" fn collect_top_level(hwnd: HWND, found: LPARAM) -> BOOL {
    let found = unsafe { &mut *(found.0 as *mut Option<HWND>) };
    let root = unsafe { GetAncestor(hwnd, GA_ROOTOWNER) };
    if usable_owner(hwnd) && (root.0.is_null() || root == hwnd) {
        *found = Some(hwnd);
        return BOOL(0);
    }
    BOOL(1)
}

/// The requested window, else our foreground window, else any visible top-level window of
/// this process.
fn resolve_owner(requested: Option<i64>) -> StoreResult<HWND> {
    if let Some(raw) = requested.filter(|&h| h != 0) {
        let hwnd = HWND(raw as isize as *mut _);
        return if usable_owner(hwnd) {
            Ok(unsafe { GetAncestor(hwnd, GA_ROOTOWNER) }).map(|root| {
                if root.0.is_null() {
                    hwnd
                } else {
                    root
                }
            })
        } else {
            Err(StoreError::new(
                "noOwnerWindow",
                format!("0x{raw:X} is not a visible window of this process"),
            ))
        };
    }
    let foreground = unsafe { GetForegroundWindow() };
    if usable_owner(foreground) {
        let root = unsafe { GetAncestor(foreground, GA_ROOTOWNER) };
        return Ok(if root.0.is_null() { foreground } else { root });
    }
    let mut found: Option<HWND> = None;
    let _ = unsafe {
        EnumWindows(
            Some(collect_top_level),
            LPARAM(&mut found as *mut _ as isize),
        )
    };
    found.ok_or_else(|| {
        StoreError::new(
            "noOwnerWindow",
            "The purchase UI needs a visible window of this app to attach to",
        )
    })
}

// -------------------------------------------------------------------------------------------
// Products
// -------------------------------------------------------------------------------------------

/// The developer-defined product id (`InAppOfferToken`), which is what the app asks for on
/// every platform; Microsoft's generated `StoreId`s stay internal.
fn app_product_id(product: &StoreProduct) -> Option<String> {
    product
        .InAppOfferToken()
        .ok()
        .map(|t| t.to_string())
        .filter(|t| !t.trim().is_empty())
}

fn associated_products(request: &Request, store: &StoreContext) -> StoreResult<Vec<StoreProduct>> {
    let result = wait(
        request,
        "GetAssociatedStoreProductsAsync",
        store.GetAssociatedStoreProductsAsync(&kinds(&ALL_KINDS)),
    )?;
    let extended = result.ExtendedError().ctx("ExtendedError")?;
    if extended.is_err() {
        return Err(winrt_error(
            "GetAssociatedStoreProductsAsync",
            &windows_core::Error::from_hresult(extended),
        ));
    }
    let mut products = Vec::new();
    for pair in result.Products().ctx("Products")? {
        products.push(pair.Value().ctx("Products.Value")?);
    }
    Ok(products)
}

fn find_product(
    request: &Request,
    store: &StoreContext,
    product_id: &str,
) -> StoreResult<StoreProduct> {
    associated_products(request, store)?
        .into_iter()
        .find(|p| app_product_id(p).as_deref() == Some(product_id))
        .ok_or_else(|| {
            StoreError::new(
                "productNotFound",
                format!(
                    "No add-on with InAppOfferToken '{product_id}' is associated with this app"
                ),
            )
        })
}

fn subscription_skus(product: &StoreProduct) -> Vec<StoreSku> {
    let Ok(skus) = product.Skus() else {
        return Vec::new();
    };
    skus.into_iter()
        .filter(|sku| sku.IsSubscription().unwrap_or(false))
        .collect()
}

fn product_kind(product: &StoreProduct) -> &'static str {
    let kind = product
        .ProductKind()
        .map(|k| k.to_string())
        .unwrap_or_default();
    if CONSUMABLE_KINDS.contains(&kind.as_str()) {
        PRODUCT_CONSUMABLE
    } else if kind == "Subscription" || !subscription_skus(product).is_empty() {
        // The Store reports subscription add-ons as `Durable` in practice; their SKUs tell.
        PRODUCT_AUTO_RENEWABLE
    } else if kind == "Durable" {
        PRODUCT_NON_CONSUMABLE
    } else {
        PRODUCT_UNKNOWN
    }
}

fn text(value: windows_core::Result<HSTRING>) -> String {
    value.map(|h| h.to_string()).unwrap_or_default()
}

fn non_blank(value: String) -> Option<String> {
    Some(value).filter(|v| !v.trim().is_empty())
}

/// The price the customer pays now (sale and trial included).
fn current_price(price: &StorePrice) -> (String, Option<String>) {
    let formatted = text(price.FormattedPrice());
    let amount = non_blank(text(price.UnformattedPrice()))
        .and_then(|u| normalize_price(&u))
        .or_else(|| normalize_price(&formatted));
    (formatted, amount)
}

/// The recurring price of a subscription SKU. While a customer is trial-eligible the Store
/// collapses `FormattedPrice` and `FormattedBasePrice` to "Free"; the charge after the trial
/// lives in `FormattedRecurrencePrice`. Only meaningful for subscriptions: one-time products
/// report a `$0` placeholder there.
fn recurring_price(price: &StorePrice) -> (String, Option<String>) {
    let (formatted, unformatted) = match non_blank(text(price.FormattedRecurrencePrice())) {
        Some(recurrence) => (recurrence, text(price.UnformattedRecurrencePrice())),
        None => (
            text(price.FormattedBasePrice()),
            text(price.UnformattedBasePrice()),
        ),
    };
    let amount = non_blank(unformatted)
        .and_then(|u| normalize_price(&u))
        .or_else(|| normalize_price(&formatted));
    (formatted, amount)
}

fn is_zero(amount: &Option<String>) -> bool {
    amount
        .as_deref()
        .is_some_and(|a| a.chars().all(|c| c == '0' || c == '.'))
}

fn subscription_of(
    sku: &StoreSku,
    price_now: &(String, Option<String>),
) -> StoreResult<SubscriptionDto> {
    let info = sku.SubscriptionInfo().ctx("SubscriptionInfo")?;
    let period = PeriodDto {
        value: info.BillingPeriod().ctx("BillingPeriod")?,
        unit: period_unit(info.BillingPeriodUnit().ctx("BillingPeriodUnit")?.0),
    };
    // `HasTrialPeriod` is product metadata and stays true for customers who already used the
    // trial; only a current price of zero says this customer gets it.
    let introductory_offer = if info.HasTrialPeriod().unwrap_or(false) && is_zero(&price_now.1) {
        Some(OfferDto {
            display_price: price_now.0.clone(),
            price: Some("0".into()),
            period: PeriodDto {
                value: info.TrialPeriod().ctx("TrialPeriod")?,
                unit: period_unit(info.TrialPeriodUnit().ctx("TrialPeriodUnit")?.0),
            },
            period_count: 1,
            payment_mode: "freeTrial",
        })
    } else {
        None
    };
    Ok(SubscriptionDto {
        period,
        group_id: None,
        introductory_offer,
        sku_id: non_blank(text(sku.StoreId())),
    })
}

fn product_dto(product: &StoreProduct) -> StoreResult<ProductDto> {
    let id = app_product_id(product)
        .ok_or_else(|| StoreError::new("platform", "Store add-on without InAppOfferToken"))?;
    let kind = product_kind(product);
    let product_price = product.Price().ctx("Price")?;
    let currency_code = non_blank(text(product_price.CurrencyCode()));

    let (display_price, price, subscription) = if kind == PRODUCT_AUTO_RENEWABLE {
        match subscription_skus(product).first() {
            Some(sku) => {
                let sku_price = sku.Price().ctx("Sku.Price")?;
                let now = current_price(&sku_price);
                let (display, amount) = recurring_price(&sku_price);
                (display, amount, Some(subscription_of(sku, &now)?))
            }
            None => {
                let (display, amount) = recurring_price(&product_price);
                (display, amount, None)
            }
        }
    } else {
        let (display, amount) = current_price(&product_price);
        (display, amount, None)
    };

    Ok(ProductDto {
        id,
        title: text(product.Title()),
        description: text(product.Description()),
        kind,
        display_price,
        price,
        currency_code,
        subscription,
        platform_id: non_blank(text(product.StoreId())),
    })
}

fn string_list(args: &Value, key: &str) -> StoreResult<Vec<String>> {
    let values = args[key].as_array().ok_or_else(|| {
        StoreError::new(
            "invalidArgument",
            format!("'{key}' must be an array of strings"),
        )
    })?;
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for value in values {
        let id = value.as_str().ok_or_else(|| {
            StoreError::new(
                "invalidArgument",
                format!("'{key}' must contain only strings"),
            )
        })?;
        if seen.insert(id.to_owned()) {
            out.push(id.to_owned());
        }
    }
    Ok(out)
}

fn required_str<'a>(args: &'a Value, key: &str) -> StoreResult<&'a str> {
    args[key]
        .as_str()
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            StoreError::new(
                "invalidArgument",
                format!("'{key}' must be a non-empty string"),
            )
        })
}

fn products(request: &Request, args: &Value) -> StoreResult<Value> {
    let ids = string_list(args, "ids")?;
    if ids.is_empty() {
        return Ok(json!([]));
    }
    let store = store_context()?;
    let available = associated_products(request, &store)?;
    let mut out = Vec::new();
    for id in &ids {
        if let Some(product) = available
            .iter()
            .find(|p| app_product_id(p).as_deref() == Some(id))
        {
            out.push(to_value(&product_dto(product)?)?);
        }
    }
    Ok(Value::Array(out))
}

// -------------------------------------------------------------------------------------------
// Licenses
// -------------------------------------------------------------------------------------------

fn app_license(request: &Request, store: &StoreContext) -> StoreResult<StoreAppLicense> {
    wait(request, "GetAppLicenseAsync", store.GetAppLicenseAsync())
}

fn app_license_dto(license: &StoreAppLicense) -> AppLicenseDto {
    let is_trial = license.IsTrial().unwrap_or(false);
    let expiration_time = license
        .ExpirationDate()
        .ok()
        .and_then(|d| store_ticks_to_unix_millis(d.UniversalTime));
    AppLicenseDto {
        is_active: license.IsActive().unwrap_or(false),
        is_trial,
        expiration_time,
        trial_time_remaining: trial_time_left(
            is_trial,
            expiration_time,
            license.TrialTimeRemaining().ok().map(|t| t.Duration),
            now_millis(),
        ),
        is_trial_owned_by_this_user: license.IsTrialOwnedByThisUser().unwrap_or(false),
        trial_unique_id: non_blank(text(license.TrialUniqueId())),
        sku_store_id: non_blank(text(license.SkuStoreId())),
    }
}

/// The license of the app itself: trial or full, active or not. Offline, like add-on licenses.
fn app_license_request(request: &Request, _args: &Value) -> StoreResult<Value> {
    let store = store_context()?;
    to_value(&app_license_dto(&app_license(request, &store)?))
}

fn add_on_licenses(license: &StoreAppLicense) -> StoreResult<Vec<StoreLicense>> {
    let mut out = Vec::new();
    for pair in license.AddOnLicenses().ctx("AddOnLicenses")? {
        out.push(pair.Value().ctx("AddOnLicenses.Value")?);
    }
    Ok(out)
}

fn license_product_id(license: &StoreLicense) -> Option<String> {
    non_blank(text(license.InAppOfferToken()))
}

fn license_expiration(license: &StoreLicense) -> Option<i64> {
    license
        .ExpirationDate()
        .ok()
        .and_then(|d| store_ticks_to_unix_millis(d.UniversalTime))
}

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

fn license_purchase(license: &StoreLicense, product_type: &'static str) -> Option<PurchaseDto> {
    let product_id = license_product_id(license)?;
    let active = license.IsActive().unwrap_or(false);
    let expiration_time = license_expiration(license);
    let product_type = match product_type {
        PRODUCT_UNKNOWN if expiration_time.is_some() => PRODUCT_AUTO_RENEWABLE,
        PRODUCT_UNKNOWN => PRODUCT_NON_CONSUMABLE,
        other => other,
    };
    Some(PurchaseDto {
        product_id,
        product_type,
        transaction_id: None,
        original_transaction_id: None,
        // The Store exposes no purchase date for an add-on license; none is invented.
        purchase_time: None,
        expiration_time,
        revocation_time: None,
        quantity: 1,
        is_auto_renewing: None,
        state: if active {
            STATE_PURCHASED
        } else {
            STATE_EXPIRED
        },
        jws: None,
        consume_token: None,
    })
}

/// `"<product StoreId>|<tracking GUID>"`: the GUID is minted once per purchase, so retrying
/// `consume` with the same purchase is deduplicated by the Store instead of fulfilling twice.
fn consume_token(store_id: &str) -> StoreResult<String> {
    let tracking = GUID::new().ctx("GUID")?;
    Ok(format!("{store_id}|{tracking:?}"))
}

fn parse_consume_token(token: &str) -> StoreResult<(String, GUID)> {
    let invalid = || StoreError::new("invalidArgument", "Not a Microsoft Store consume token");
    let (store_id, tracking) = token.split_once('|').ok_or_else(invalid)?;
    if store_id.trim().is_empty() || store_id.contains('/') {
        return Err(invalid());
    }
    let guid =
        GUID::try_from(tracking.trim_matches(|c| c == '{' || c == '}')).map_err(|_| invalid())?;
    Ok((store_id.to_owned(), guid))
}

fn consumable_purchase(product: &StoreProduct) -> StoreResult<Option<PurchaseDto>> {
    let Some(product_id) = app_product_id(product) else {
        return Ok(None);
    };
    let store_id = text(product.StoreId());
    Ok(Some(PurchaseDto {
        product_id,
        product_type: PRODUCT_CONSUMABLE,
        transaction_id: None,
        original_transaction_id: None,
        purchase_time: None,
        expiration_time: None,
        revocation_time: None,
        quantity: 1,
        is_auto_renewing: None,
        state: STATE_PURCHASED,
        jws: None,
        consume_token: Some(consume_token(store_id_from_sku_store_id(&store_id))?),
    }))
}

/// Active add-on licenses (works offline), plus consumables the user bought and the app has
/// not fulfilled yet (needs the service; skipped when it cannot be reached).
fn purchases(request: &Request, _args: &Value) -> StoreResult<Value> {
    let store = store_context()?;
    let license = app_license(request, &store)?;
    let collection = wait(
        request,
        "GetUserCollectionAsync",
        store.GetUserCollectionAsync(&kinds(&ALL_KINDS)),
    )
    .ok();
    let mut kinds_by_id = std::collections::HashMap::new();
    let mut out = Vec::new();
    if let Some(collection) = &collection {
        for pair in collection.Products().ctx("Products")? {
            let product = pair.Value().ctx("Products.Value")?;
            let kind = product_kind(&product);
            if let Some(id) = app_product_id(&product) {
                kinds_by_id.insert(id, kind);
            }
            if kind == PRODUCT_CONSUMABLE {
                if let Some(purchase) = consumable_purchase(&product)? {
                    out.push(to_value(&purchase)?);
                }
            }
        }
    }
    for license in add_on_licenses(&license)? {
        if !license.IsActive().unwrap_or(false) {
            continue;
        }
        let kind = license_product_id(&license)
            .and_then(|id| kinds_by_id.get(&id).copied())
            .unwrap_or(PRODUCT_UNKNOWN);
        if kind == PRODUCT_CONSUMABLE {
            continue;
        }
        if let Some(purchase) = license_purchase(&license, kind) {
            out.push(to_value(&purchase)?);
        }
    }
    Ok(Value::Array(out))
}

fn entitlement(request: &Request, args: &Value) -> StoreResult<Value> {
    let product_id = required_str(args, "productId")?;
    let store = store_context()?;
    let license = app_license(request, &store)?;
    // `AddOnLicenses` is keyed by SKU StoreId, not by the developer id: scan.
    let found = add_on_licenses(&license)?
        .into_iter()
        .find(|l| license_product_id(l).as_deref() == Some(product_id));
    let dto = match found {
        Some(license) => {
            let active = license.IsActive().unwrap_or(false);
            let expiration_time = license_expiration(&license);
            let state = if active {
                ENTITLEMENT_ACTIVE
            } else if expiration_time.is_some_and(|e| e <= now_millis()) {
                ENTITLEMENT_EXPIRED
            } else {
                ENTITLEMENT_NOT_OWNED
            };
            EntitlementDto {
                product_id: product_id.to_owned(),
                state,
                expiration_time,
                will_auto_renew: None,
                purchase: license_purchase(&license, PRODUCT_UNKNOWN).filter(|_| active),
            }
        }
        None => EntitlementDto {
            product_id: product_id.to_owned(),
            state: ENTITLEMENT_NOT_OWNED,
            expiration_time: None,
            will_auto_renew: None,
            purchase: None,
        },
    };
    to_value(&dto)
}

// -------------------------------------------------------------------------------------------
// Purchase / consume
// -------------------------------------------------------------------------------------------

/// `"success"`, `"alreadyOwned"` or `"cancelled"`; any other outcome is a typed error.
fn purchase_outcome(result: &StorePurchaseResult) -> StoreResult<&'static str> {
    let status = result.Status().ctx("Status")?;
    let extended = result.ExtendedError().unwrap_or(HRESULT(0));
    Ok(match status {
        StorePurchaseStatus::Succeeded => "success",
        StorePurchaseStatus::AlreadyPurchased => "alreadyOwned",
        StorePurchaseStatus::NotPurchased
            if extended.is_ok() || matches!(extended.0, E_ABORT | HRESULT_ERROR_CANCELLED) =>
        {
            "cancelled"
        }
        _ if extended.0 == IAP_E_NOT_ASSOCIATED => {
            return Err(winrt_error(
                "RequestPurchaseAsync",
                &windows_core::Error::from_hresult(extended),
            ));
        }
        StorePurchaseStatus::NetworkError => {
            return Err(StoreError::with_hresult(
                "network",
                "The Store could not be reached",
                extended.0,
            ));
        }
        StorePurchaseStatus::ServerError => {
            return Err(StoreError::with_hresult(
                "server",
                "The Store service returned an error",
                extended.0,
            ));
        }
        other => {
            return Err(winrt_error(
                &format!("RequestPurchaseAsync (status {})", other.0),
                &windows_core::Error::from_hresult(extended),
            ));
        }
    })
}

fn purchase(request: &Request, args: &Value) -> StoreResult<Value> {
    let product_id = required_str(args, "productId")?;
    let sku_id = args["skuStoreId"].as_str().filter(|s| !s.trim().is_empty());
    let owner = resolve_owner(args["ownerWindow"].as_i64())?;
    let store = store_context()?;
    attach_owner(&store, owner)?;
    let product = find_product(request, &store, product_id)?;
    let kind = product_kind(&product);

    let operation = match sku_id {
        Some(sku_id) => {
            let sku = product
                .Skus()
                .ctx("Skus")?
                .into_iter()
                .find(|s| text(s.StoreId()) == sku_id)
                .ok_or_else(|| {
                    StoreError::new(
                        "productNotFound",
                        format!("'{product_id}' has no SKU '{sku_id}'"),
                    )
                })?;
            sku.RequestPurchaseAsync()
        }
        None => product.RequestPurchaseAsync(),
    };
    let result = wait(request, "RequestPurchaseAsync", operation)?;
    let outcome = purchase_outcome(&result)?;
    if outcome == "cancelled" {
        return Ok(json!({ "status": outcome }));
    }

    let purchase = if kind == PRODUCT_CONSUMABLE {
        consumable_purchase(&product)?
    } else {
        // The license usually reflects the purchase at once; if it does not yet, report the
        // purchase without an expiration rather than failing a completed payment.
        let license = app_license(request, &store)
            .ok()
            .and_then(|l| add_on_licenses(&l).ok());
        license
            .and_then(|all| {
                all.into_iter()
                    .find(|l| license_product_id(l).as_deref() == Some(product_id))
            })
            .and_then(|l| license_purchase(&l, kind))
            .map(|mut p| {
                p.state = STATE_PURCHASED;
                p
            })
            .or_else(|| {
                Some(PurchaseDto {
                    product_id: product_id.to_owned(),
                    product_type: kind,
                    transaction_id: None,
                    original_transaction_id: None,
                    purchase_time: None,
                    expiration_time: None,
                    revocation_time: None,
                    quantity: 1,
                    is_auto_renewing: None,
                    state: STATE_PURCHASED,
                    jws: None,
                    consume_token: None,
                })
            })
    };
    Ok(json!({ "status": outcome, "purchase": purchase.map(|p| to_value(&p)).transpose()? }))
}

/// Buys the app itself: a trial converts to the full license. The license change also arrives
/// through `OfflineLicensesChanged`.
fn purchase_app(request: &Request, args: &Value) -> StoreResult<Value> {
    let owner = resolve_owner(args["ownerWindow"].as_i64())?;
    let store = store_context()?;
    attach_owner(&store, owner)?;
    let found = wait(
        request,
        "GetStoreProductForCurrentAppAsync",
        store.GetStoreProductForCurrentAppAsync(),
    )?;
    let extended = found.ExtendedError().ctx("ExtendedError")?;
    if extended.is_err() {
        return Err(winrt_error(
            "GetStoreProductForCurrentAppAsync",
            &windows_core::Error::from_hresult(extended),
        ));
    }
    let product = found.Product().ctx("Product")?;
    let result = wait(
        request,
        "RequestPurchaseAsync",
        product.RequestPurchaseAsync(),
    )?;
    let outcome = purchase_outcome(&result)?;
    if outcome == "cancelled" {
        return Ok(json!({ "status": outcome }));
    }
    let purchase = PurchaseDto {
        product_id: text(product.StoreId()),
        product_type: PRODUCT_NON_CONSUMABLE,
        transaction_id: None,
        original_transaction_id: None,
        purchase_time: None,
        expiration_time: None,
        revocation_time: None,
        quantity: 1,
        is_auto_renewing: None,
        state: STATE_PURCHASED,
        jws: None,
        consume_token: None,
    };
    Ok(json!({ "status": outcome, "purchase": to_value(&purchase)? }))
}

fn consume(request: &Request, args: &Value) -> StoreResult<Value> {
    let (store_id, tracking) = parse_consume_token(required_str(args, "token")?)?;
    let quantity = args["quantity"].as_u64().unwrap_or(1);
    let quantity = u32::try_from(quantity)
        .ok()
        .filter(|&q| q > 0)
        .ok_or_else(|| {
            StoreError::new(
                "invalidArgument",
                "'quantity' must be between 1 and 4294967295",
            )
        })?;
    let store = store_context()?;
    let result = wait(
        request,
        "ReportConsumableFulfillmentAsync",
        store.ReportConsumableFulfillmentAsync(
            &HSTRING::from(store_id.as_str()),
            quantity,
            tracking,
        ),
    )?;
    let extended = result.ExtendedError().unwrap_or(HRESULT(0));
    if extended.0 == IAP_E_NOT_ASSOCIATED {
        return Err(winrt_error(
            "ReportConsumableFulfillmentAsync",
            &windows_core::Error::from_hresult(extended),
        ));
    }
    match result.Status().ctx("Status")? {
        StoreConsumableStatus::Succeeded => {
            Ok(json!({ "balanceRemaining": result.BalanceRemaining().unwrap_or(0) }))
        }
        StoreConsumableStatus::InsufficentQuantity => Err(StoreError::with_hresult(
            "insufficientQuantity",
            "Not enough remaining balance to consume",
            extended.0,
        )),
        StoreConsumableStatus::NetworkError => Err(StoreError::with_hresult(
            "network",
            "The Store could not be reached",
            extended.0,
        )),
        StoreConsumableStatus::ServerError => Err(StoreError::with_hresult(
            "server",
            "The Store service returned an error",
            extended.0,
        )),
        other => Err(winrt_error(
            &format!("ReportConsumableFulfillmentAsync (status {})", other.0),
            &windows_core::Error::from_hresult(extended),
        )),
    }
}

fn availability() -> StoreResult<Value> {
    Ok(json!({ "state": if packaged() { "available" } else { "notPackaged" } }))
}

/// Lets tests drive the real Store API from an unpackaged process (`diag.allowUnpackaged`).
static ALLOW_UNPACKAGED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Everything but `availability` needs package identity; failing fast gives the app a typed
/// answer instead of whatever the Store service makes of an unpackaged caller.
fn require_package() -> StoreResult<()> {
    if packaged() || ALLOW_UNPACKAGED.load(std::sync::atomic::Ordering::Relaxed) {
        Ok(())
    } else {
        Err(not_packaged_error())
    }
}

pub fn dispatch(request: &Request, method: &str, args: &Value) -> StoreResult<Value> {
    let _com = init_thread();
    type Handler = fn(&Request, &Value) -> StoreResult<Value>;
    let handler: Handler = match method {
        "availability" => return availability(),
        "diag.allowUnpackaged" => {
            let allow = args["value"].as_bool().unwrap_or(false);
            ALLOW_UNPACKAGED.store(allow, std::sync::atomic::Ordering::Relaxed);
            return Ok(Value::Bool(allow));
        }
        "products" => products,
        // The Store has no separate "sync": the OS keeps the license current.
        "purchases" | "restore" => purchases,
        "entitlement" => entitlement,
        "purchase" => purchase,
        "appLicense" => app_license_request,
        "purchaseApp" => purchase_app,
        "consume" => consume,
        _ => {
            return Err(StoreError::new(
                "invalidArgument",
                format!("unknown method '{method}'"),
            ))
        }
    };
    require_package()?;
    handler(request, args)
}

// -------------------------------------------------------------------------------------------
// Out-of-band updates
// -------------------------------------------------------------------------------------------

/// Subscribes to `OfflineLicensesChanged` (purchases made elsewhere, renewals, refunds). Each
/// change is reported with the full list of active licenses; the Kotlin side diffs it.
pub fn start_listener(sink: &'static dyn Sink) -> bool {
    if !packaged() {
        return false;
    }
    let mut guard = listener();
    if guard.is_some() {
        return true;
    }
    match register_in_mta(sink) {
        Ok(registered) => {
            *guard = Some(registered);
            true
        }
        Err(_) => false,
    }
}

/// Registers the handler from a thread of our own in the MTA, whatever thread asked.
///
/// The caller is usually the UI thread, an STA (tao's `OleInitialize`): registering there tied
/// the `StoreContext` to that apartment instead of the MTA every request uses, and no license
/// change ever reached the app on a real Store install. The thread exits once registered; the
/// pinned MTA and the agile context keep the subscription alive.
fn register_in_mta(sink: &'static dyn Sink) -> windows_core::Result<Listener> {
    std::thread::Builder::new()
        .name("nucleus-iap-listener".into())
        .spawn(move || {
            let _com = init_thread();
            let store = StoreContext::GetDefault()?;
            let token = store.OfflineLicensesChanged(&license_handler(sink))?;
            Ok(Listener {
                _store: store,
                _token: token,
                apartment: current_apartment(),
            })
        })
        .map_err(|_| windows_core::Error::from_hresult(HRESULT(E_ABORT)))?
        .join()
        .unwrap_or_else(|_| Err(windows_core::Error::from_hresult(HRESULT(E_ABORT))))
}

fn license_handler(
    sink: &'static dyn Sink,
) -> TypedEventHandler<StoreContext, windows_core::IInspectable> {
    TypedEventHandler::new(move |_, _| {
        // Never block the Store's callback thread on another Store call.
        let _ = std::thread::Builder::new()
            .name("nucleus-iap-licenses".into())
            .spawn(move || {
                let _com = init_thread();
                let (app_license, purchases) = snapshot_licenses();
                sink.event(
                    json!({
                        "type": "licensesChanged",
                        "appLicense": app_license,
                        "purchases": purchases,
                    })
                    .to_string(),
                );
            });
        Ok(())
    })
}

/// The COM apartment of the calling thread (`APTTYPE`), `-1` when it has none.
fn current_apartment() -> i32 {
    let mut kind = APTTYPE(-1);
    let mut qualifier = APTTYPEQUALIFIER(0);
    match unsafe { CoGetApartmentType(&mut kind, &mut qualifier) } {
        Ok(()) => kind.0,
        Err(_) => -1,
    }
}

/// The app license (`null` when unreadable) and the active add-on licenses.
fn snapshot_licenses() -> (Value, Value) {
    let Ok(store) = store_context() else {
        return (Value::Null, json!([]));
    };
    let Ok(license) = store.GetAppLicenseAsync().and_then(|op| op.join()) else {
        return (Value::Null, json!([]));
    };
    let app = to_value(&app_license_dto(&license)).unwrap_or(Value::Null);
    let Ok(licenses) = add_on_licenses(&license) else {
        return (app, json!([]));
    };
    let purchases = Value::Array(
        licenses
            .iter()
            .filter(|l| l.IsActive().unwrap_or(false))
            .filter_map(|l| license_purchase(l, PRODUCT_UNKNOWN))
            .filter_map(|p| to_value(&p).ok())
            .collect(),
    );
    (app, purchases)
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::System::Com::{
        CoInitializeEx, APTTYPE_MAINSTA, APTTYPE_MTA, APTTYPE_STA, COINIT_APARTMENTTHREADED,
    };

    #[test]
    fn consume_token_round_trips() {
        let token = consume_token("9NBLGGH69M0B").unwrap();
        let (store_id, guid) = parse_consume_token(&token).unwrap();
        assert_eq!(store_id, "9NBLGGH69M0B");
        assert_eq!(format!("9NBLGGH69M0B|{guid:?}"), token);
    }

    #[test]
    fn consume_tokens_are_unique_per_purchase() {
        let tokens: HashSet<_> = (0..10_000).map(|_| consume_token("9N").unwrap()).collect();
        assert_eq!(tokens.len(), 10_000);
    }

    #[test]
    fn consume_token_rejects_garbage() {
        for bad in [
            "",
            "|",
            "9N",
            "9N|",
            "|{00000000-0000-0000-0000-000000000000}",
            "9N/0001|00000000-0000-0000-0000-000000000000",
            "9N|not-a-guid",
            "9N|00000000-0000-0000-0000-00000000000G",
        ] {
            assert!(parse_consume_token(bad).is_err(), "{bad:?} was accepted");
        }
        assert!(parse_consume_token("9N|{01234567-89AB-CDEF-0123-456789ABCDEF}").is_ok());
        assert!(parse_consume_token("9N|01234567-89ab-cdef-0123-456789abcdef").is_ok());
    }

    #[test]
    fn winrt_errors_are_classified() {
        let classify =
            |h: u32| winrt_error("x", &windows_core::Error::from_hresult(HRESULT(h as i32))).code;
        assert_eq!(classify(0x8000_4004), "cancelled");
        assert_eq!(classify(0x8007_04C7), "cancelled");
        assert_eq!(classify(0x8007_0578), "noOwnerWindow");
        assert_eq!(classify(0x8007_2EE7), "network");
        assert_eq!(classify(0x8007_2EFD), "network");
        assert_eq!(classify(0x8019_0194), "network");
        assert_eq!(classify(0x803F_6107), "notAssociated");
        assert_eq!(classify(0x803F_6100), "platform");
        assert_eq!(classify(0x8007_0005), "platform");
    }

    #[test]
    fn the_listener_subscribes_in_the_mta_even_from_an_sta() {
        struct Silent;
        impl Sink for Silent {
            fn respond(&self, _: i64, _: String) {}
            fn event(&self, _: String) {}
        }
        static SILENT: Silent = Silent;
        // The UI thread that usually asks is an STA (tao's OleInitialize).
        let apartment = std::thread::spawn(|| {
            unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) }.unwrap();
            // STA, or MAINSTA when it is the process's first one.
            assert!([APTTYPE_STA.0, APTTYPE_MAINSTA.0].contains(&current_apartment()));
            let listener = register_in_mta(&SILENT).expect("OfflineLicensesChanged subscription");
            listener.apartment
        })
        .join()
        .unwrap();
        assert_eq!(apartment, APTTYPE_MTA.0);
    }

    #[test]
    fn test_process_is_not_packaged() {
        assert!(!packaged());
        let error = require_package().unwrap_err();
        assert_eq!(error.code, "notPackaged");
        assert_eq!(error.hresult, Some(0x8007_3D54_u32 as i32));
    }

    #[test]
    fn owner_resolution_rejects_foreign_and_bogus_windows() {
        assert_eq!(
            resolve_owner(Some(0x1234_5678)).unwrap_err().code,
            "noOwnerWindow"
        );
        assert_eq!(resolve_owner(Some(-1)).unwrap_err().code, "noOwnerWindow");
        // The desktop window exists but belongs to another process.
        let desktop = unsafe { windows::Win32::UI::WindowsAndMessaging::GetDesktopWindow() };
        assert_eq!(
            resolve_owner(Some(desktop.0 as i64)).unwrap_err().code,
            "noOwnerWindow"
        );
        // A console test process owns no visible top-level window.
        assert_eq!(resolve_owner(None).unwrap_err().code, "noOwnerWindow");
    }
}

/// Exploratory probe against the real Store API; run with
/// `cargo test --release probe_real_store -- --ignored --nocapture`.
#[cfg(test)]
mod probe {
    use super::*;
    use crate::requests;

    fn run(id: i64, method: &str, args: Value) -> StoreResult<Value> {
        let request = requests::begin(id).unwrap();
        let out = dispatch(&request, method, &args);
        requests::finish(id);
        out
    }

    #[test]
    #[ignore]
    fn probe_real_store() {
        ALLOW_UNPACKAGED.store(true, std::sync::atomic::Ordering::Relaxed);
        let _com = init_thread();
        let t = std::time::Instant::now();
        println!("availability: {:?}", run(1, "availability", json!({})));
        println!(
            "products: {:?} ({:?})",
            run(2, "products", json!({"ids": ["pro", "coins"]})),
            t.elapsed()
        );
        println!(
            "purchases: {:?} ({:?})",
            run(3, "purchases", json!({})),
            t.elapsed()
        );
        println!(
            "entitlement: {:?} ({:?})",
            run(4, "entitlement", json!({"productId": "pro"})),
            t.elapsed()
        );
        println!(
            "purchase: {:?}",
            run(5, "purchase", json!({"productId": "pro"}))
        );
        println!(
            "consume: {:?} ({:?})",
            run(
                6,
                "consume",
                json!({"token": "9NBLGGH69M0B|{01234567-89AB-CDEF-0123-456789ABCDEF}"})
            ),
            t.elapsed()
        );
        println!(
            "listener: {}",
            start_listener(Box::leak(Box::new(NullSink)))
        );

        // Cancel a real WinRT operation mid-flight.
        let request = requests::begin(7).unwrap();
        let canceller = std::thread::spawn(|| {
            std::thread::sleep(std::time::Duration::from_millis(5));
            requests::cancel(7);
        });
        let t = std::time::Instant::now();
        let out = dispatch(&request, "products", &json!({"ids": ["pro"]}));
        canceller.join().unwrap();
        requests::finish(7);
        println!("cancelled products: {:?} after {:?}", out, t.elapsed());
    }

    struct NullSink;
    impl Sink for NullSink {
        fn respond(&self, _: i64, _: String) {}
        fn event(&self, json: String) {
            println!("event: {json}");
        }
    }
}

#[cfg(test)]
mod probe_init {
    use super::*;
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DestroyWindow, WINDOW_EX_STYLE, WS_OVERLAPPEDWINDOW, WS_VISIBLE,
    };
    use windows_core::w;

    fn window() -> HWND {
        unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE(0),
                w!("STATIC"),
                w!("probe"),
                WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                0,
                0,
                100,
                100,
                None,
                None,
                None,
                None,
            )
            .unwrap()
        }
    }

    #[test]
    #[ignore]
    fn probe_initialize_with_window() {
        let _com = init_thread();
        let a = StoreContext::GetDefault().unwrap();
        let b = StoreContext::GetDefault().unwrap();
        println!("GetDefault same instance: {}", a.as_raw() == b.as_raw());
        let (w1, w2) = (window(), window());
        let init: IInitializeWithWindow = a.cast().unwrap();
        println!("init w1: {:?}", unsafe { init.Initialize(w1) });
        println!("init w1 again: {:?}", unsafe { init.Initialize(w1) });
        println!("init w2: {:?}", unsafe { init.Initialize(w2) });
        unsafe { DestroyWindow(w1).unwrap() };
        println!("init w2 after w1 destroyed: {:?}", unsafe {
            init.Initialize(w2)
        });
        let init_c: IInitializeWithWindow = StoreContext::GetDefault().unwrap().cast().unwrap();
        println!("fresh GetDefault init w2: {:?}", unsafe {
            init_c.Initialize(w2)
        });
        println!(
            "resolve_owner(None) with our window: {:?}",
            resolve_owner(None).map(|h| h == w2)
        );
        println!(
            "resolve_owner(Some(w2)): {:?}",
            resolve_owner(Some(w2.0 as i64)).map(|h| h == w2)
        );
    }
}
