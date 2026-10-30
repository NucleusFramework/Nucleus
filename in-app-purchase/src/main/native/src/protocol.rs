//! The JSON wire format shared with `NativeIapBridge` (Kotlin).
//!
//! Every request is answered exactly once with either `{"ok": <payload>}` or
//! `{"error": {"code": "...", "message": "...", "hresult": <i32?>}}`. Payload shapes mirror
//! `dev.nucleusframework.iap.internal.Wire*` — keep both sides in sync.
//!
//! Portions derived from tauri-plugin-iap (https://github.com/Choochmeque/tauri-plugin-iap),
//! Copyright (c) 2025 Vladimir Pankratov, MIT License.

use serde::Serialize;
use serde_json::{json, Value};

/// A failure the Kotlin side maps onto `StoreErrorCode` by `code`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoreError {
    pub code: &'static str,
    pub message: String,
    pub hresult: Option<i32>,
}

impl StoreError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            hresult: None,
        }
    }

    pub fn with_hresult(code: &'static str, message: impl Into<String>, hresult: i32) -> Self {
        Self {
            code,
            message: message.into(),
            hresult: Some(hresult),
        }
    }
}

pub type StoreResult<T> = Result<T, StoreError>;

pub fn ok_envelope(payload: Value) -> String {
    json!({ "ok": payload }).to_string()
}

pub fn error_envelope(error: &StoreError) -> String {
    json!({
        "error": {
            "code": error.code,
            "message": error.message,
            "hresult": error.hresult,
        }
    })
    .to_string()
}

pub fn to_value<T: Serialize>(value: &T) -> StoreResult<Value> {
    serde_json::to_value(value)
        .map_err(|e| StoreError::new("internal", format!("Failed to encode response: {e}")))
}

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PeriodDto {
    pub value: u32,
    pub unit: &'static str,
}

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct OfferDto {
    pub display_price: String,
    pub price: Option<String>,
    pub period: PeriodDto,
    pub period_count: u32,
    pub payment_mode: &'static str,
}

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SubscriptionDto {
    pub period: PeriodDto,
    pub group_id: Option<String>,
    pub introductory_offer: Option<OfferDto>,
    /// Windows: the SKU `StoreId` this subscription was read from.
    pub sku_id: Option<String>,
}

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProductDto {
    pub id: String,
    pub title: String,
    pub description: String,
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub display_price: String,
    pub price: Option<String>,
    pub currency_code: Option<String>,
    pub subscription: Option<SubscriptionDto>,
    /// Windows: the Microsoft-generated product `StoreId`.
    pub platform_id: Option<String>,
}

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PurchaseDto {
    pub product_id: String,
    pub product_type: &'static str,
    pub transaction_id: Option<String>,
    pub original_transaction_id: Option<String>,
    pub purchase_time: Option<i64>,
    pub expiration_time: Option<i64>,
    pub revocation_time: Option<i64>,
    pub quantity: u32,
    pub is_auto_renewing: Option<bool>,
    pub state: &'static str,
    pub jws: Option<String>,
    pub consume_token: Option<String>,
}

#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EntitlementDto {
    pub product_id: String,
    pub state: &'static str,
    pub expiration_time: Option<i64>,
    pub will_auto_renew: Option<bool>,
    pub purchase: Option<PurchaseDto>,
}

/// The license of the app itself, as opposed to its add-ons.
#[derive(Serialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AppLicenseDto {
    pub is_active: bool,
    pub is_trial: bool,
    /// When the license (the trial, for a trial) ends; `None` for one that never does.
    pub expiration_time: Option<i64>,
    /// Milliseconds of trial left; `None` outside a trial or for an unlimited one.
    pub trial_time_remaining: Option<i64>,
    pub is_trial_owned_by_this_user: bool,
    pub trial_unique_id: Option<String>,
    pub sku_store_id: Option<String>,
}

/// A `TimeSpan` beyond this is the Store's "unlimited" sentinel, not a real duration.
const UNLIMITED_SPAN_MILLIS: i64 = 100 * 365 * 24 * 60 * 60 * 1000;

/// A WinRT `TimeSpan` (100 ns ticks) as milliseconds; `None` for the unlimited sentinel.
pub fn span_ticks_to_millis(ticks: i64) -> Option<i64> {
    let millis = ticks.max(0) / 10_000;
    (millis < UNLIMITED_SPAN_MILLIS).then_some(millis)
}

/// Whether an add-on license still entitles: one that never expires, or one that ends later.
pub fn license_still_valid(expiration_millis: Option<i64>, now_millis: i64) -> bool {
    expiration_millis.is_none_or(|end| end > now_millis)
}

/// What is left of a trial, in milliseconds; `None` outside a trial and for an unlimited one.
///
/// A time-limited trial ends at the license's `ExpirationDate`: `TrialTimeRemaining` only
/// counts down usage-limited trials and reports the unlimited sentinel for a timed one
/// (observed on a 7-day Store trial), so the expiration wins whenever there is one.
pub fn trial_time_left(
    is_trial: bool,
    expiration_millis: Option<i64>,
    remaining_span_ticks: Option<i64>,
    now_millis: i64,
) -> Option<i64> {
    if !is_trial {
        return None;
    }
    match expiration_millis {
        Some(end) => Some((end - now_millis).max(0)),
        None => remaining_span_ticks.and_then(span_ticks_to_millis),
    }
}

pub const PRODUCT_CONSUMABLE: &str = "consumable";
pub const PRODUCT_NON_CONSUMABLE: &str = "nonConsumable";
pub const PRODUCT_AUTO_RENEWABLE: &str = "autoRenewable";
pub const PRODUCT_UNKNOWN: &str = "unknown";

pub const STATE_PURCHASED: &str = "purchased";
pub const STATE_EXPIRED: &str = "expired";

pub const ENTITLEMENT_ACTIVE: &str = "active";
pub const ENTITLEMENT_EXPIRED: &str = "expired";
pub const ENTITLEMENT_NOT_OWNED: &str = "notOwned";

/// Microsoft Store and StoreKit duration units, as the wire spells them.
pub fn period_unit(index: i32) -> &'static str {
    match index {
        0 => "minute",
        1 => "hour",
        2 => "day",
        3 => "week",
        4 => "month",
        5 => "year",
        _ => "unknown",
    }
}

/// 100-ns ticks between 1601-01-01 (FILETIME / `Foundation::DateTime`) and the Unix epoch.
const UNIX_EPOCH_TICKS: i64 = 116_444_736_000_000_000;

/// Past this (year 9000) a Store date is a "never" sentinel: durables without an expiry report
/// `DateTime` 9999-12-31, which must not reach the app as a real expiration date.
const NEVER_UNIX_MILLIS: i64 = 221_845_392_000_000;

/// Converts `Foundation::DateTime::UniversalTime` to Unix milliseconds; `None` for the zero
/// value and for far-future "never" sentinels.
pub fn store_ticks_to_unix_millis(ticks: i64) -> Option<i64> {
    if ticks <= 0 {
        return None;
    }
    let millis = (ticks - UNIX_EPOCH_TICKS).div_euclid(10_000);
    if millis >= NEVER_UNIX_MILLIS {
        None
    } else {
        Some(millis)
    }
}

/// Microsoft formats SKU `StoreId`s as `<product StoreId>/<SKU>` (`9NBLGGH69M0B/000N`);
/// `ReportConsumableFulfillmentAsync` wants the product part only.
pub fn store_id_from_sku_store_id(sku_store_id: &str) -> &str {
    sku_store_id
        .split_once('/')
        .map_or(sku_store_id, |(prefix, _)| prefix)
}

/// Normalizes a store price string to a plain decimal (`"1234.56"`), or `None` when it holds
/// no number (`"Free"`, `""`).
///
/// The Store's `Unformatted*Price` strings are locale-formatted too, so the decimal separator
/// is inferred: with both `.` and `,` present the last one is the decimal mark; a single kind
/// repeated is grouping (`1.234.567`); a single occurrence followed by exactly three digits is
/// grouping (`1,234`), anything else decimal (`4,99`). Apostrophes, spaces and NBSPs group.
pub fn normalize_price(text: &str) -> Option<String> {
    let mut start = text.find(|c: char| c.is_ascii_digit())?;
    let end = text.rfind(|c: char| c.is_ascii_digit())? + 1;
    // A bare leading decimal mark (".99") belongs to the number.
    if text[..start].ends_with(['.', ',']) {
        start -= 1;
    }
    let core: Vec<char> = text[start..end].chars().collect();

    let is_separator = |c: char| c == '.' || c == ',';
    let is_grouping =
        |c: char| c == '\'' || c == '\u{2019}' || c == ' ' || c == '\u{a0}' || c == '\u{202f}';
    if core
        .iter()
        .any(|&c| !(c.is_ascii_digit() || is_separator(c) || is_grouping(c)))
    {
        // Two numbers in one string ("2 for 3.99"): refuse rather than guess.
        return None;
    }

    let last_dot = core.iter().rposition(|&c| c == '.');
    let last_comma = core.iter().rposition(|&c| c == ',');
    let decimal_at = match (last_dot, last_comma) {
        (Some(d), Some(c)) => Some(d.max(c)),
        (Some(p), None) | (None, Some(p)) => {
            let mark = core[p];
            let occurrences = core.iter().filter(|&&c| c == mark).count();
            let digits_after = core[p + 1..]
                .iter()
                .take_while(|c| c.is_ascii_digit())
                .count();
            let trailing = core.len() - p - 1;
            if occurrences > 1 || (digits_after == 3 && trailing == 3) {
                None
            } else {
                Some(p)
            }
        }
        (None, None) => None,
    };

    let mut out = String::with_capacity(core.len() + 1);
    for (i, &c) in core.iter().enumerate() {
        if c.is_ascii_digit() {
            out.push(c);
        } else if Some(i) == decimal_at {
            out.push('.');
        }
    }
    if out.starts_with('.') {
        out.insert(0, '0');
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_license_entitles_until_it_expires() {
        assert!(license_still_valid(None, 1_000));
        assert!(license_still_valid(Some(2_000), 1_000));
        assert!(!license_still_valid(Some(1_000), 1_000));
        assert!(!license_still_valid(Some(500), 1_000));
    }

    #[test]
    fn a_timed_trial_counts_down_to_its_expiration() {
        let day = 86_400_000;
        // The Store's answer for a 7-day trial: an expiration, and the unlimited span.
        assert_eq!(
            trial_time_left(true, Some(7 * day), Some(i64::MAX), 0),
            Some(7 * day)
        );
        assert_eq!(trial_time_left(true, Some(day), None, 3 * day), Some(0));
        // Usage-limited trial: no expiration, a real span.
        assert_eq!(
            trial_time_left(true, None, Some(6_000_000_000), 0),
            Some(600_000)
        );
        assert_eq!(trial_time_left(true, None, Some(i64::MAX), 0), None);
        assert_eq!(trial_time_left(false, Some(day), Some(10), 0), None);
    }

    #[test]
    fn spans_convert_and_unlimited_is_none() {
        // 7 days, as a trial reports it.
        assert_eq!(span_ticks_to_millis(6_048_000_000_000), Some(604_800_000));
        assert_eq!(span_ticks_to_millis(0), Some(0));
        assert_eq!(span_ticks_to_millis(-5), Some(0));
        assert_eq!(span_ticks_to_millis(i64::MAX), None);
    }

    #[test]
    fn ticks_epoch_is_zero() {
        assert_eq!(store_ticks_to_unix_millis(UNIX_EPOCH_TICKS), Some(0));
    }

    #[test]
    fn ticks_known_dates() {
        assert_eq!(
            store_ticks_to_unix_millis(133_443_936_000_000_000),
            Some(1_699_920_000_000)
        );
        assert_eq!(
            store_ticks_to_unix_millis(125_911_584_000_000_000),
            Some(946_684_800_000)
        );
        assert_eq!(
            store_ticks_to_unix_millis(UNIX_EPOCH_TICKS + 5_000_000),
            Some(500)
        );
    }

    #[test]
    fn ticks_before_epoch_floor_to_negative_millis() {
        assert_eq!(store_ticks_to_unix_millis(UNIX_EPOCH_TICKS - 1), Some(-1));
        assert_eq!(
            store_ticks_to_unix_millis(116_129_376_000_000_000),
            Some(-31_536_000_000)
        );
    }

    #[test]
    fn ticks_zero_negative_and_sentinels_are_none() {
        assert_eq!(store_ticks_to_unix_millis(0), None);
        assert_eq!(store_ticks_to_unix_millis(-1), None);
        assert_eq!(store_ticks_to_unix_millis(i64::MIN), None);
        assert_eq!(store_ticks_to_unix_millis(i64::MAX), None);
        // 9999-12-31T23:59:59Z, what the Store reports for a durable that never expires.
        assert_eq!(store_ticks_to_unix_millis(2_650_467_743_990_000_000), None);
    }

    #[test]
    fn ticks_far_but_real_future_is_kept() {
        // 2100-01-01T00:00:00Z
        assert_eq!(
            store_ticks_to_unix_millis(157_469_184_000_000_000),
            Some(4_102_444_800_000)
        );
    }

    #[test]
    fn sku_store_id_parsing() {
        assert_eq!(
            store_id_from_sku_store_id("9MSPC6MP8FM4/000N"),
            "9MSPC6MP8FM4"
        );
        assert_eq!(store_id_from_sku_store_id("9MSPC6MP8FM4"), "9MSPC6MP8FM4");
        assert_eq!(store_id_from_sku_store_id("/000N"), "");
        assert_eq!(store_id_from_sku_store_id("A/B/C"), "A");
        assert_eq!(store_id_from_sku_store_id(""), "");
    }

    #[test]
    fn price_plain_and_symbols() {
        assert_eq!(normalize_price("4.99").as_deref(), Some("4.99"));
        assert_eq!(normalize_price("$4.99").as_deref(), Some("4.99"));
        assert_eq!(normalize_price("9.99 USD").as_deref(), Some("9.99"));
        assert_eq!(normalize_price("$10").as_deref(), Some("10"));
        assert_eq!(normalize_price("$0.00").as_deref(), Some("0.00"));
    }

    #[test]
    fn price_european_formats() {
        // The upstream parser read these as 499 and 123456 units.
        assert_eq!(normalize_price("4,99 €").as_deref(), Some("4.99"));
        assert_eq!(normalize_price("1.234,56 €").as_deref(), Some("1234.56"));
        assert_eq!(normalize_price("1 234,56 €").as_deref(), Some("1234.56"));
        assert_eq!(
            normalize_price("1\u{a0}234,56\u{a0}€").as_deref(),
            Some("1234.56")
        );
        assert_eq!(
            normalize_price("1\u{202f}234,56 €").as_deref(),
            Some("1234.56")
        );
        assert_eq!(normalize_price("CHF 1'234.50").as_deref(), Some("1234.50"));
        assert_eq!(
            normalize_price("1\u{2019}234.50 CHF").as_deref(),
            Some("1234.50")
        );
    }

    #[test]
    fn price_grouping_only() {
        assert_eq!(normalize_price("$1,234").as_deref(), Some("1234"));
        assert_eq!(normalize_price("¥1,200").as_deref(), Some("1200"));
        assert_eq!(normalize_price("1.234.567 ₫").as_deref(), Some("1234567"));
        assert_eq!(
            normalize_price("₹1,23,456.00").as_deref(),
            Some("123456.00")
        );
        assert_eq!(normalize_price("₩12,000").as_deref(), Some("12000"));
    }

    #[test]
    fn price_leading_decimal() {
        assert_eq!(normalize_price(".99").as_deref(), Some("0.99"));
        assert_eq!(normalize_price("$,50").as_deref(), Some("0.50"));
    }

    #[test]
    fn price_without_number() {
        assert_eq!(normalize_price(""), None);
        assert_eq!(normalize_price("Free"), None);
        assert_eq!(normalize_price("Gratuit"), None);
        assert_eq!(normalize_price("   "), None);
        assert_eq!(normalize_price("$"), None);
    }

    #[test]
    fn price_with_two_numbers_is_refused() {
        assert_eq!(normalize_price("2 for 3.99"), None);
        assert_eq!(normalize_price("3.99/1 month"), None);
    }

    #[test]
    fn price_non_ascii_digits_are_not_numbers() {
        // Arabic-Indic digits: the Store never uses them in Unformatted*Price; refuse.
        assert_eq!(normalize_price("٤٫٩٩"), None);
    }

    #[test]
    fn price_fuzz_never_panics_and_output_is_decimal() {
        let alphabet: Vec<char> = "0123456789.,' \u{a0}$€-abcé٤".chars().collect();
        let mut seed: u64 = 0x9E37_79B9_7F4A_7C15;
        for _ in 0..200_000 {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            let len = (seed % 12) as usize;
            let mut s = String::new();
            let mut x = seed;
            for _ in 0..len {
                s.push(alphabet[(x % alphabet.len() as u64) as usize]);
                x = x.rotate_left(7).wrapping_mul(31);
            }
            if let Some(out) = normalize_price(&s) {
                let body = out.as_str();
                assert!(!body.is_empty(), "{s:?} -> {out:?}");
                assert!(
                    body.chars().filter(|&c| c == '.').count() <= 1,
                    "{s:?} -> {out:?}"
                );
                assert!(
                    body.chars().all(|c| c.is_ascii_digit() || c == '.'),
                    "{s:?} -> {out:?}"
                );
                assert!(!body.starts_with('.'), "{s:?} -> {out:?}");
            }
        }
    }

    #[test]
    fn period_units() {
        let units: Vec<_> = (-1..=6).map(period_unit).collect();
        assert_eq!(
            units,
            ["unknown", "minute", "hour", "day", "week", "month", "year", "unknown"]
        );
    }

    #[test]
    fn envelopes_escape_everything() {
        let nasty = "quote\" backslash\\ nul\u{0} newline\n emoji😀 rtl\u{202e}";
        let raw = error_envelope(&StoreError::with_hresult("x", nasty, -2_147_024_809));
        let back: Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(back["error"]["message"], nasty);
        assert_eq!(back["error"]["hresult"], -2_147_024_809);
        let raw = ok_envelope(json!({ "s": nasty }));
        let back: Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(back["ok"]["s"], nasty);
    }

    #[test]
    fn error_without_hresult_encodes_null() {
        let raw = error_envelope(&StoreError::new("productNotFound", "nope"));
        let back: Value = serde_json::from_str(&raw).unwrap();
        assert!(back["error"]["hresult"].is_null());
    }

    #[test]
    fn product_dto_field_names() {
        let dto = ProductDto {
            id: "pro".into(),
            title: "Pro".into(),
            description: "d".into(),
            kind: PRODUCT_AUTO_RENEWABLE,
            display_price: "$1".into(),
            price: Some("1".into()),
            currency_code: Some("USD".into()),
            subscription: Some(SubscriptionDto {
                period: PeriodDto {
                    value: 1,
                    unit: "month",
                },
                group_id: None,
                introductory_offer: Some(OfferDto {
                    display_price: "Free".into(),
                    price: None,
                    period: PeriodDto {
                        value: 7,
                        unit: "day",
                    },
                    period_count: 1,
                    payment_mode: "freeTrial",
                }),
                sku_id: Some("9N/0001".into()),
            }),
            platform_id: Some("9N".into()),
        };
        let v = to_value(&dto).unwrap();
        assert_eq!(v["type"], "autoRenewable");
        assert_eq!(v["displayPrice"], "$1");
        assert_eq!(v["currencyCode"], "USD");
        assert_eq!(v["platformId"], "9N");
        assert_eq!(
            v["subscription"]["introductoryOffer"]["paymentMode"],
            "freeTrial"
        );
        assert_eq!(v["subscription"]["introductoryOffer"]["periodCount"], 1);
        assert_eq!(v["subscription"]["skuId"], "9N/0001");
    }
}
