// Cross-platform AccessKit helpers shared by Linux and Windows projectors.
//
// The Compose → wire → AccessKit TreeUpdate path is identical on both OSes;
// only the platform Adapter (accesskit_unix / accesskit_windows) differs.

pub mod jvm;
pub mod tree;
pub mod wire;
