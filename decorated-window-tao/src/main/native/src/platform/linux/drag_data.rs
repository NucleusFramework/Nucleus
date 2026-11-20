#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct DropPosition {
    pub x: i32,
    pub y: i32,
    pub time: u32,
}

#[derive(Clone, Copy)]
enum Request {
    Preview,
    Drop,
}

#[derive(Debug, PartialEq, Eq)]
pub(super) enum ReceivedData {
    Preview,
    RequestDrop(DropPosition),
    Drop(DropPosition),
    Ignore,
}

/// What an inbound drag carries, as read from the one target GTK negotiated:
/// local files (as paths), plain text, and non-file URIs (#930). Mirrors
/// Kotlin `TaoDragAndDropPayload`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(super) struct InboundPayload {
    pub files: Vec<String>,
    pub text: Option<String>,
    pub uris: Vec<String>,
}

impl InboundPayload {
    /// Splits a `text/uri-list` into files and other URIs. Every `file:` URI is
    /// a file, converted with `to_path` (which may hand a remote `file://host/`
    /// URI back unchanged: it still is not a URI the app can open as a link);
    /// anything else (`https:`, `mailto:`, …) is kept verbatim as a URI.
    pub fn from_uri_list<F: Fn(&str) -> String>(uris: &[String], to_path: F) -> Self {
        let mut payload = Self::default();
        for uri in uris.iter().map(|uri| uri.trim()).filter(|uri| !uri.is_empty()) {
            if is_file_uri(uri) {
                payload.files.push(to_path(uri));
            } else {
                payload.uris.push(uri.to_owned());
            }
        }
        payload
    }

    pub fn from_text(text: Option<String>) -> Self {
        Self {
            text: text.filter(|text| !text.is_empty()),
            ..Self::default()
        }
    }
}

fn is_file_uri(uri: &str) -> bool {
    uri.get(..5).is_some_and(|scheme| scheme.eq_ignore_ascii_case("file:"))
}

#[derive(Default)]
pub(super) struct DragDataState {
    preview_requested: bool,
    request: Option<Request>,
    drop_position: Option<DropPosition>,
}

impl DragDataState {
    pub fn request_preview(&mut self) -> bool {
        if self.preview_requested || self.request.is_some() || self.drop_position.is_some() {
            return false;
        }
        self.preview_requested = true;
        self.request = Some(Request::Preview);
        true
    }

    pub fn request_drop(&mut self, position: DropPosition) -> bool {
        self.drop_position = Some(position);
        if self.request.is_some() {
            return false;
        }
        self.request = Some(Request::Drop);
        true
    }

    pub fn receive(&mut self) -> ReceivedData {
        match self.request.take() {
            Some(Request::Preview) => match self.drop_position {
                Some(position) => {
                    self.request = Some(Request::Drop);
                    ReceivedData::RequestDrop(position)
                }
                None => ReceivedData::Preview,
            },
            Some(Request::Drop) => self
                .drop_position
                .take()
                .map(ReceivedData::Drop)
                .unwrap_or(ReceivedData::Ignore),
            None => ReceivedData::Ignore,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const POSITION: DropPosition = DropPosition {
        x: 300,
        y: 40,
        time: 99,
    };

    #[test]
    fn uri_list_splits_files_from_links() {
        let uris = [
            "file:///tmp/a%20b.txt".to_owned(),
            "https://example.com/x?q=1".to_owned(),
            " FILE:///tmp/c ".to_owned(),
            "".to_owned(),
            "mailto:a@b.c".to_owned(),
        ];
        let payload = InboundPayload::from_uri_list(&uris, |uri| format!("path:{uri}"));
        assert_eq!(payload.files, ["path:file:///tmp/a%20b.txt", "path:FILE:///tmp/c"]);
        assert_eq!(payload.uris, ["https://example.com/x?q=1", "mailto:a@b.c"]);
        assert_eq!(payload.text, None);
    }

    #[test]
    fn empty_text_is_no_text() {
        assert_eq!(InboundPayload::from_text(Some(String::new())), InboundPayload::default());
        assert_eq!(
            InboundPayload::from_text(Some("שלום\nworld".to_owned())).text.as_deref(),
            Some("שלום\nworld"),
        );
    }

    #[test]
    fn preview_is_requested_once_and_does_not_complete_a_drop() {
        let mut state = DragDataState::default();
        assert!(state.request_preview());
        assert!(!state.request_preview());
        assert_eq!(state.receive(), ReceivedData::Preview);
        assert!(!state.request_preview());
        assert_eq!(state.receive(), ReceivedData::Ignore);
    }

    #[test]
    fn release_during_preview_waits_then_requests_actual_drop_data() {
        let mut state = DragDataState::default();
        assert!(state.request_preview());
        assert!(!state.request_drop(POSITION));
        assert_eq!(state.receive(), ReceivedData::RequestDrop(POSITION));
        assert_eq!(state.receive(), ReceivedData::Drop(POSITION));
        assert_eq!(state.receive(), ReceivedData::Ignore);
    }

    #[test]
    fn drop_without_preview_uses_release_coordinates() {
        let mut state = DragDataState::default();
        assert!(state.request_drop(POSITION));
        assert_eq!(state.receive(), ReceivedData::Drop(POSITION));
    }

    #[test]
    fn completed_preview_is_followed_by_a_separate_drop_request() {
        let mut state = DragDataState::default();
        assert!(state.request_preview());
        assert_eq!(state.receive(), ReceivedData::Preview);
        assert!(state.request_drop(POSITION));
        assert_eq!(state.receive(), ReceivedData::Drop(POSITION));
    }
}
