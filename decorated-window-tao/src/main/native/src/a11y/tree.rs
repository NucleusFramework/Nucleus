// Shared AccessKit tree helpers (focus clamp, reachability).

use std::collections::{HashMap, HashSet};

use accesskit::NodeId;

/// Replays AccessKit's reachability rule over a child map: a node is live iff
/// it was sent (present as a key) and is reachable from the root by following
/// children.
pub fn reachable_nodes(
    root: Option<NodeId>,
    child_map: &HashMap<NodeId, Vec<NodeId>>,
) -> HashSet<NodeId> {
    let mut live = HashSet::new();
    let Some(root) = root else {
        return live;
    };
    let mut stack = vec![root];
    while let Some(id) = stack.pop() {
        let Some(kids) = child_map.get(&id) else {
            continue;
        };
        if !live.insert(id) {
            continue;
        }
        stack.extend(kids.iter().copied());
    }
    live
}
