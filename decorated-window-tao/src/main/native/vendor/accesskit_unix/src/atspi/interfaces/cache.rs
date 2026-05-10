// Copyright 2022 The AccessKit Authors. All rights reserved.
// Licensed under the Apache License, Version 2.0 (found in
// the LICENSE-APACHE file) or the MIT license (found in
// the LICENSE-MIT file), at your option.
//
// Vendored fork addition: empty AT-SPI Cache interface stub.
//
// AT-SPI clients (Atspi, Orca, Accerciser) try to call
// `org.a11y.atspi.Cache.GetItems` on `/org/a11y/atspi/cache` of every
// registered application as a fast-path bulk fetch. accesskit_unix doesn't
// populate a cache, so without this stub every client emits
//   `dbind-WARNING: AT-SPI: Error in GetItems, sender=:1.X,
//    error=Unknown object '/org/a11y/atspi/cache'`
// and falls back to walking the tree node-by-node.
//
// We register an empty Cache interface so the call returns a valid empty
// reply instead of "Unknown object". Real AT-SPI clients then walk the tree
// (which is fast for accesskit anyway). If we ever populate a cache, replace
// the empty Vec with an actual snapshot — the wire shape is already correct.

use crate::atspi::OwnedObjectAddress;
use zbus::{fdo, interface};

pub(crate) struct CacheInterface;

/// AT-SPI cache item per spec:
///   (object_address, parent, children, interfaces, name, role, description, state)
/// = ((so)(so)a(so)assusau)
type CacheItem = (
    OwnedObjectAddress,      // path
    OwnedObjectAddress,      // parent
    Vec<OwnedObjectAddress>, // children
    Vec<String>,             // interfaces
    String,                  // name
    u32,                     // role
    String,                  // description
    Vec<u32>,                // state (length 2 in real implementations)
);

#[interface(name = "org.a11y.atspi.Cache")]
impl CacheInterface {
    fn get_items(&self) -> fdo::Result<Vec<CacheItem>> {
        // Empty cache — clients fall back to per-node Accessible.GetChildren.
        Ok(Vec::new())
    }
}
