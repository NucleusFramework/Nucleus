// Forward AccessKit ActionRequest upcalls to the JVM NativeTaoBridge helpers.

use accesskit::{Action, ActionData, ActionRequest};
use jni::objects::JValue;

use crate::a11y::wire::{
    A_CLICK, A_DECREMENT, A_INCREMENT, A_REQUEST_FOCUS, A_SCROLL_DOWN, A_SCROLL_LEFT,
    A_SCROLL_RIGHT, A_SCROLL_UP, A_SET_TEXT,
};
use crate::state::JAVA_VM;

pub fn forward_action_to_jvm(
    handle: i64,
    request: ActionRequest,
    meta_actions: u16,
    custom_count: usize,
) {
    let Some(jvm) = JAVA_VM.get() else { return };
    let Ok(mut env) = jvm.attach_current_thread() else { return };
    let class_name = "dev/nucleusframework/window/tao/ffi/NativeTaoBridge";
    let node_id = request.target_node.0 as i64;

    // Map back to the bitmask the Kotlin controller already understands.
    // Compose-side scroll handling expects a SCROLL_UP/DOWN/LEFT/RIGHT bit
    // chosen by the AT direction; AccessKit's `SetScrollOffset` carries an
    // explicit point we forward via `dispatchA11yScrollBy`.
    let action_bit: i32 = match request.action {
        Action::Click => A_CLICK as i32,
        Action::Focus => A_REQUEST_FOCUS as i32,
        Action::Blur => 0,
        Action::Increment => A_INCREMENT as i32,
        Action::Decrement => A_DECREMENT as i32,
        Action::ScrollUp => A_SCROLL_UP as i32,
        Action::ScrollDown => A_SCROLL_DOWN as i32,
        Action::ScrollLeft => A_SCROLL_LEFT as i32,
        Action::ScrollRight => A_SCROLL_RIGHT as i32,
        Action::Expand => A_CLICK as i32, // routed through the click handler
        Action::Collapse => A_CLICK as i32,
        _ => 0,
    };

    let class = match env.find_class(class_name) {
        Ok(c) => c,
        Err(_) => return,
    };

    match request.action {
        Action::ReplaceSelectedText => {
            if meta_actions & A_SET_TEXT == 0 {
                eprintln!("[a11y] ReplaceSelectedText ignored: A_SET_TEXT not set on node {}", node_id);
                return;
            }
            let text = match request.data {
                Some(ActionData::Value(s)) => s.into_string(),
                _ => {
                    eprintln!("[a11y] ReplaceSelectedText ignored: missing ActionData::Value");
                    return;
                }
            };
            eprintln!("[a11y] ReplaceSelectedText -> dispatchA11ySetText(node={}, text={:?})", node_id, text);
            let Ok(jstr) = env.new_string(&text) else { return };
            let _ = env.call_static_method(
                class,
                "dispatchA11ySetText",
                "(JJLjava/lang/String;)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Object(&jstr.into()),
                ],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
        }
        Action::SetValue => {
            // Slider / progress value setter. AT-SPI's
            // `Value.SetCurrentValue(d)` dispatches this with NumericValue.
            // Compose's `SemanticsActions.SetProgress` expects the absolute
            // value in the slider's own range (clamped on the JVM side).
            let value = match request.data {
                Some(ActionData::NumericValue(v)) => v,
                _ => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11ySetValue",
                "(JJD)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Double(value),
                ],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
        }
        Action::SetScrollOffset | Action::ScrollToPoint => {
            // Both carry a target point; forward as an absolute scroll
            // delta. The Kotlin observer handles `onScrollBy(dx, dy)` by
            // passing through to `SemanticsActions.ScrollBy`.
            let (dx, dy) = match request.data {
                Some(ActionData::SetScrollOffset(p)) => (p.x as f32, p.y as f32),
                Some(ActionData::ScrollToPoint(p)) => (p.x as f32, p.y as f32),
                _ => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11yScrollBy",
                "(JJFF)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Float(dx),
                    JValue::Float(dy),
                ],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
        }
        Action::SetTextSelection => {
            let sel = match request.data {
                Some(ActionData::SetTextSelection(s)) => s,
                _ => return,
            };
            let _ = env.call_static_method(
                class,
                "dispatchA11ySetSelection",
                "(JJII)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Int(sel.anchor.character_index as i32),
                    JValue::Int(sel.focus.character_index as i32),
                ],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
        }
        Action::CustomAction => {
            let idx = match request.data {
                Some(ActionData::CustomAction(i)) => i,
                _ => return,
            };
            if idx < 0 || (idx as usize) >= custom_count {
                return;
            }
            let _ = env.call_static_method(
                class,
                "dispatchA11yCustomAction",
                "(JJI)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Int(idx),
                ],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
        }
        _ => {
            if action_bit == 0 {
                return;
            }
            let _ = env.call_static_method(
                class,
                "dispatchA11yActionByNsView",
                "(JJI)V",
                &[
                    JValue::Long(handle),
                    JValue::Long(node_id),
                    JValue::Int(action_bit),
                ],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
        }
    }
}
