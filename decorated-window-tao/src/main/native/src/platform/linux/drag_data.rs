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
