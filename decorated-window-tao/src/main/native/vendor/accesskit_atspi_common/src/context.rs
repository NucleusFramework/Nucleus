// Copyright 2023 The AccessKit Authors. All rights reserved.
// Licensed under the Apache License, Version 2.0 (found in
// the LICENSE-APACHE file) or the MIT license (found in
// the LICENSE-MIT file), at your option.

use accesskit::{ActionHandler, ActionRequest};
use accesskit_consumer::Tree;
use std::fmt::{Debug, Formatter};
use std::sync::{Arc, Mutex, RwLock, RwLockReadGuard, RwLockWriteGuard};

use crate::WindowBounds;

/// This is an implementation detail of `accesskit_unix`, required for robust
/// state transitions with minimal overhead.
pub trait ActionHandlerNoMut {
    fn do_action(&self, request: ActionRequest);
}

/// This is an implementation detail of `accesskit_unix`, required for robust
/// state transitions with minimal overhead.
pub struct ActionHandlerWrapper<H: ActionHandler + Send>(Mutex<H>);

impl<H: 'static + ActionHandler + Send> ActionHandlerWrapper<H> {
    pub fn new(inner: H) -> Self {
        Self(Mutex::new(inner))
    }
}

impl<H: ActionHandler + Send> ActionHandlerNoMut for ActionHandlerWrapper<H> {
    fn do_action(&self, request: ActionRequest) {
        self.0.lock().unwrap().do_action(request)
    }
}

pub(crate) struct Context {
    pub(crate) app_context: Arc<RwLock<AppContext>>,
    pub(crate) tree: RwLock<Tree>,
    pub(crate) action_handler: Arc<dyn ActionHandlerNoMut + Send + Sync>,
    pub(crate) root_window_bounds: RwLock<WindowBounds>,
}

impl Debug for Context {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Context")
            .field("app_context", &self.app_context)
            .field("tree", &self.tree)
            .field("action_handler", &"ActionHandler")
            .field("root_window_bounds", &self.root_window_bounds)
            .finish()
    }
}

impl Context {
    pub(crate) fn new(
        app_context: &Arc<RwLock<AppContext>>,
        tree: Tree,
        action_handler: Arc<dyn ActionHandlerNoMut + Send + Sync>,
        root_window_bounds: WindowBounds,
    ) -> Arc<Self> {
        Arc::new(Self {
            app_context: Arc::clone(app_context),
            tree: RwLock::new(tree),
            action_handler,
            root_window_bounds: RwLock::new(root_window_bounds),
        })
    }

    pub(crate) fn read_tree(&self) -> RwLockReadGuard<'_, Tree> {
        self.tree.read().unwrap()
    }

    pub(crate) fn read_root_window_bounds(&self) -> RwLockReadGuard<'_, WindowBounds> {
        self.root_window_bounds.read().unwrap()
    }

    pub fn do_action(&self, request: ActionRequest) {
        self.action_handler.do_action(request);
    }

    pub(crate) fn read_app_context(&self) -> RwLockReadGuard<'_, AppContext> {
        self.app_context.read().unwrap()
    }

    pub(crate) fn write_app_context(&self) -> RwLockWriteGuard<'_, AppContext> {
        self.app_context.write().unwrap()
    }
}

#[derive(Debug)]
pub struct AppContext {
    pub(crate) name: Option<String>,
    pub(crate) toolkit_name: Option<String>,
    pub(crate) toolkit_version: Option<String>,
    pub(crate) id: Option<i32>,
    pub(crate) adapters: Vec<(usize, Arc<Context>)>,
}

impl AppContext {
    pub fn new(name: Option<String>) -> Arc<RwLock<Self>> {
        Arc::new(RwLock::new(Self {
            name,
            toolkit_name: None,
            toolkit_version: None,
            id: None,
            adapters: Vec::new(),
        }))
    }

    pub(crate) fn adapter_index(&self, id: usize) -> Result<usize, usize> {
        self.adapters.binary_search_by(|adapter| adapter.0.cmp(&id))
    }

    // PATCH(nucleus): keep `adapters` ordered by id. `adapter_index` searches it
    // with `binary_search_by`, which is only defined on a sorted slice, while
    // this pushed to the end — so an id registered out of order (adapters are
    // created on the toolkit thread but registered from the AT-SPI worker) made
    // every later lookup unreliable, and the two `unwrap()`s on that lookup
    // aborted the whole process. Inserting at the searched position keeps the
    // invariant the search assumes; a duplicate id replaces its entry rather
    // than shadowing it.
    pub(crate) fn push_adapter(&mut self, id: usize, context: &Arc<Context>) {
        match self.adapter_index(id) {
            Ok(index) => self.adapters[index] = (id, Arc::clone(context)),
            Err(index) => self.adapters.insert(index, (id, Arc::clone(context))),
        }
    }

    pub(crate) fn remove_adapter(&mut self, id: usize) {
        if let Ok(index) = self.adapter_index(id) {
            self.adapters.remove(index);
        }
    }
}
