//! Duplex Win32 named-pipe IPC client linking Oink's input/DnD edge detector to DWC
//! (`\\.\pipe\dwc-oink-bridge`).
//!
//! All pipe connections, polling, and reconnect backoff run off the real-time input thread
//! inside a background worker thread communicated with via bounded non-blocking `try_send`.

use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread::{self, JoinHandle};
use std::time::Duration;

pub const DEFAULT_DWC_PIPE_NAME: &str = r"\\.\pipe\dwc-oink-bridge";

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum BezelEdge {
    Left,
    #[default]
    Right,
    Top,
    Bottom,
}

impl BezelEdge {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Left => "left",
            Self::Right => "right",
            Self::Top => "top",
            Self::Bottom => "bottom",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "left" | "LEFT" => Some(Self::Left),
            "right" | "RIGHT" => Some(Self::Right),
            "top" | "TOP" => Some(Self::Top),
            "bottom" | "BOTTOM" => Some(Self::Bottom),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct WindowRect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DwcEventKind {
    DragBezelCross,
    DragMove,
    DragEnd,
    CursorEdge,
}

impl DwcEventKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::DragBezelCross => "drag_bezel_cross",
            Self::DragMove => "drag_move",
            Self::DragEnd => "drag_end",
            Self::CursorEdge => "cursor_edge",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DwcBridgeEvent {
    pub kind: DwcEventKind,
    pub hwnd: u64,
    pub edge: BezelEdge,
    pub window_rect: WindowRect,
    pub cursor_x: i32,
    pub cursor_y: i32,
    pub velocity_x: f64,
    pub velocity_y: f64,
    pub dnd_push: bool,
    pub dragging: bool,
}

#[derive(Serialize)]
struct WireEvent<'a> {
    #[serde(rename = "type")]
    kind: &'a str,
    hwnd: String,
    edge: &'a str,
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
    cursor_x: i32,
    cursor_y: i32,
    vx: f64,
    vy: f64,
    dnd_push: bool,
    dragging: bool,
}

impl DwcBridgeEvent {
    pub fn to_json_line(&self) -> String {
        let wire = WireEvent {
            kind: self.kind.as_str(),
            hwnd: format!("0x{:x}", self.hwnd),
            edge: self.edge.as_str(),
            left: self.window_rect.left,
            top: self.window_rect.top,
            right: self.window_rect.right,
            bottom: self.window_rect.bottom,
            cursor_x: self.cursor_x,
            cursor_y: self.cursor_y,
            vx: self.velocity_x,
            vy: self.velocity_y,
            dnd_push: self.dnd_push,
            dragging: self.dragging,
        };
        serde_json::to_string(&wire).unwrap_or_default()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DwcPolicy {
    pub shared_edge: BezelEdge,
    pub dnd_push_px: i32,
    pub suppress_aero_snap: bool,
    pub option_b_span: bool,
}

impl Default for DwcPolicy {
    fn default() -> Self {
        Self {
            shared_edge: BezelEdge::Right,
            dnd_push_px: 28,
            suppress_aero_snap: true,
            option_b_span: true,
        }
    }
}

#[derive(Deserialize)]
struct WirePolicy {
    #[serde(rename = "type")]
    msg_type: String,
    #[serde(default)]
    shared_edge: String,
    #[serde(default = "default_push_px")]
    dnd_push_px: i32,
    #[serde(default = "default_true")]
    suppress_aero_snap: bool,
    #[serde(default = "default_true")]
    option_b_span: bool,
}

fn default_push_px() -> i32 {
    28
}

fn default_true() -> bool {
    true
}

impl DwcPolicy {
    pub fn parse_json_line(line: &str) -> Option<Self> {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return None;
        }
        let wire: WirePolicy = serde_json::from_str(trimmed).ok()?;
        if wire.msg_type != "bezel_policy" {
            return None;
        }
        Some(Self {
            shared_edge: BezelEdge::parse(&wire.shared_edge).unwrap_or(BezelEdge::Right),
            dnd_push_px: wire.dnd_push_px.clamp(4, 256),
            suppress_aero_snap: wire.suppress_aero_snap,
            option_b_span: wire.option_b_span,
        })
    }
}

#[cfg(windows)]
pub fn query_active_dragged_window() -> Option<(u64, WindowRect)> {
    use windows::Win32::Foundation::RECT;
    use windows::Win32::UI::WindowsAndMessaging::{GUI_INMOVESIZE, GUITHREADINFO, GetGUIThreadInfo, GetWindowRect};

    let mut gti = GUITHREADINFO {
        cbSize: std::mem::size_of::<GUITHREADINFO>() as u32,
        ..Default::default()
    };
    // SAFETY: `gti.cbSize` is initialized to the exact struct size and `gti` is uniquely borrowed.
    let ok = unsafe { GetGUIThreadInfo(0, &mut gti) }.is_ok();
    if !ok || !gti.flags.contains(GUI_INMOVESIZE) || gti.hwndMoveSize.0.is_null() {
        return None;
    }

    let mut rc = RECT::default();
    // SAFETY: `gti.hwndMoveSize` is non-null from `GetGUIThreadInfo` and `rc` is a valid out-parameter.
    if unsafe { GetWindowRect(gti.hwndMoveSize, &mut rc) }.is_err() {
        return None;
    }

    Some((
        gti.hwndMoveSize.0 as usize as u64,
        WindowRect {
            left: rc.left,
            top: rc.top,
            right: rc.right,
            bottom: rc.bottom,
        },
    ))
}

#[cfg(not(windows))]
pub fn query_active_dragged_window() -> Option<(u64, WindowRect)> {
    None
}

#[cfg(windows)]
fn query_cursor_pos() -> (i32, i32) {
    use windows::Win32::Foundation::POINT;
    use windows::Win32::UI::WindowsAndMessaging::GetCursorPos;

    let mut pt = POINT::default();
    // SAFETY: `pt` is a stack-allocated Win32 POINT out-parameter.
    if unsafe { GetCursorPos(&mut pt) }.is_ok() {
        (pt.x, pt.y)
    } else {
        (0, 0)
    }
}

#[cfg(not(windows))]
fn query_cursor_pos() -> (i32, i32) {
    (0, 0)
}

#[derive(Debug)]
enum WorkerCmd {
    Probe {
        cursor_x: i32,
        cursor_y: i32,
    },
    WindowDrag {
        hwnd: u64,
        rect: WindowRect,
        cursor_x: i32,
        cursor_y: i32,
        dnd_push: bool,
    },
    Unprobe,
    Shutdown,
}

#[derive(Debug)]
pub struct DwcBridgeHandle {
    tx: Option<mpsc::SyncSender<WorkerCmd>>,
    connected: Arc<AtomicBool>,
    policy: Arc<Mutex<DwcPolicy>>,
    worker: Option<JoinHandle<()>>,
}

impl DwcBridgeHandle {
    pub fn disabled() -> Self {
        Self {
            tx: None,
            connected: Arc::new(AtomicBool::new(false)),
            policy: Arc::new(Mutex::new(DwcPolicy::default())),
            worker: None,
        }
    }

    pub fn start(pipe_name: impl Into<String>, on_connected_changed: Option<Arc<dyn Fn(bool) + Send + Sync>>) -> Self {
        Self::start_with_callbacks(pipe_name, on_connected_changed, None)
    }

    pub fn start_with_callbacks(
        pipe_name: impl Into<String>,
        on_connected_changed: Option<Arc<dyn Fn(bool) + Send + Sync>>,
        on_window_drag_changed: Option<Arc<dyn Fn(bool) + Send + Sync>>,
    ) -> Self {
        let pipe_name = pipe_name.into();
        let connected = Arc::new(AtomicBool::new(false));
        let policy = Arc::new(Mutex::new(DwcPolicy::default()));
        let (tx, rx) = mpsc::sync_channel::<WorkerCmd>(64);

        let worker_connected = Arc::clone(&connected);
        let worker_policy = Arc::clone(&policy);
        let worker = thread::Builder::new()
            .name("dwc-oink-bridge".to_string())
            .spawn(move || {
                worker_loop(
                    pipe_name,
                    rx,
                    worker_connected,
                    worker_policy,
                    on_connected_changed,
                    on_window_drag_changed,
                );
            })
            .ok();

        Self {
            tx: worker.as_ref().map(|_| tx),
            connected,
            policy,
            worker,
        }
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Acquire)
    }

    pub fn policy(&self) -> DwcPolicy {
        *self.policy.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Non-blocking notification forwarded from Oink's real-time `DndCmd::Probe`.
    pub fn notify_probe(&self, cursor_x: i32, cursor_y: i32) {
        if !self.is_connected() {
            return;
        }
        if let Some(tx) = &self.tx {
            let _ = tx.try_send(WorkerCmd::Probe { cursor_x, cursor_y });
        }
    }

    /// Explicit window-drag crossing event (deterministic test & synthetic drag helper).
    pub fn notify_window_drag(&self, hwnd: u64, rect: WindowRect, cursor_x: i32, cursor_y: i32, dnd_push: bool) {
        if let Some(tx) = &self.tx {
            let _ = tx.try_send(WorkerCmd::WindowDrag {
                hwnd,
                rect,
                cursor_x,
                cursor_y,
                dnd_push,
            });
        }
    }

    pub fn notify_unprobe(&self) {
        if !self.is_connected() {
            return;
        }
        if let Some(tx) = &self.tx {
            let _ = tx.try_send(WorkerCmd::Unprobe);
        }
    }

    pub fn shutdown(&mut self) {
        if let Some(tx) = self.tx.take() {
            let _ = tx.send(WorkerCmd::Shutdown);
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

impl Drop for DwcBridgeHandle {
    fn drop(&mut self) {
        self.shutdown();
    }
}

#[cfg(windows)]
fn encode_wide_nul(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}

#[cfg(windows)]
struct PipeClient {
    handle: windows_sys::Win32::Foundation::HANDLE,
    rx_buf: String,
    active_drag_hwnd: Option<u64>,
    active_drag_synthetic: bool,
    last_sent_rect: Option<WindowRect>,
}

#[cfg(windows)]
impl PipeClient {
    fn connect(pipe_wide: &[u16]) -> Option<Self> {
        use windows_sys::Win32::Foundation::INVALID_HANDLE_VALUE;
        use windows_sys::Win32::Storage::FileSystem::{
            CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_GENERIC_READ, FILE_GENERIC_WRITE, OPEN_EXISTING,
        };

        // SAFETY: `pipe_wide` is NUL-terminated UTF-16 and all flags follow Win32 named-pipe client open semantics.
        let handle = unsafe {
            CreateFileW(
                pipe_wide.as_ptr(),
                FILE_GENERIC_READ | FILE_GENERIC_WRITE,
                0,
                std::ptr::null(),
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL,
                std::ptr::null_mut(),
            )
        };
        if handle == INVALID_HANDLE_VALUE || handle.is_null() {
            None
        } else {
            Some(Self {
                handle,
                rx_buf: String::new(),
                active_drag_hwnd: None,
                active_drag_synthetic: false,
                last_sent_rect: None,
            })
        }
    }

    fn drain_policies(&mut self, policy_store: &Arc<Mutex<DwcPolicy>>) -> bool {
        use windows_sys::Win32::Storage::FileSystem::ReadFile;
        use windows_sys::Win32::System::Pipes::PeekNamedPipe;

        loop {
            let mut avail: u32 = 0;
            // SAFETY: `self.handle` is an open named pipe handle and `avail` is a valid out-pointer.
            let peek_ok = unsafe {
                PeekNamedPipe(
                    self.handle,
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut(),
                    &mut avail,
                    std::ptr::null_mut(),
                )
            };
            if peek_ok == 0 {
                return false;
            }
            if avail == 0 {
                return true;
            }

            let mut buf = [0_u8; 1024];
            let chunk = avail.min(buf.len() as u32);
            let mut read: u32 = 0;
            // SAFETY: `self.handle` is valid and `buf` provides `chunk` writable bytes.
            let rd_ok = unsafe {
                ReadFile(
                    self.handle,
                    buf.as_mut_ptr(),
                    chunk,
                    &mut read,
                    std::ptr::null_mut(),
                )
            };
            if rd_ok == 0 || read == 0 {
                return false;
            }
            let text = String::from_utf8_lossy(&buf[..read as usize]);
            self.rx_buf.push_str(&text);

            while let Some(nl) = self.rx_buf.find('\n') {
                let line = self.rx_buf[..nl].trim_end_matches('\r');
                if let Some(pol) = DwcPolicy::parse_json_line(line) {
                    *policy_store.lock().unwrap_or_else(|e| e.into_inner()) = pol;
                }
                self.rx_buf.drain(..=nl);
            }
            if self.rx_buf.len() > 65536 {
                self.rx_buf.clear();
            }
        }
    }

    fn write_event(&self, ev: &DwcBridgeEvent) -> bool {
        use windows_sys::Win32::Storage::FileSystem::WriteFile;

        let mut line = ev.to_json_line();
        line.push('\n');
        let bytes = line.as_bytes();
        let mut written: u32 = 0;
        // SAFETY: `self.handle` is a valid open pipe handle and `bytes` stays pinned across `WriteFile`.
        let ok = unsafe {
            WriteFile(
                self.handle,
                bytes.as_ptr(),
                bytes.len() as u32,
                &mut written,
                std::ptr::null_mut(),
            )
        };
        ok != 0 && written as usize == bytes.len()
    }
}

#[cfg(windows)]
impl Drop for PipeClient {
    fn drop(&mut self) {
        use windows_sys::Win32::Foundation::CloseHandle;

        // SAFETY: `self.handle` is exclusively owned by `PipeClient` and closed once on Drop.
        unsafe {
            CloseHandle(self.handle);
        }
    }
}

#[cfg(windows)]
fn worker_loop(
    pipe_name: String,
    rx: mpsc::Receiver<WorkerCmd>,
    connected: Arc<AtomicBool>,
    policy_store: Arc<Mutex<DwcPolicy>>,
    on_connected_changed: Option<Arc<dyn Fn(bool) + Send + Sync>>,
    on_window_drag_changed: Option<Arc<dyn Fn(bool) + Send + Sync>>,
) {
    let pipe_wide = encode_wide_nul(&pipe_name);
    let set_conn = |state: bool| {
        if connected.swap(state, Ordering::AcqRel) != state
            && let Some(cb) = &on_connected_changed
        {
            cb(state);
        }
    };
    let mut drag_active_state = false;
    let mut set_drag_active = |active: bool| {
        if drag_active_state != active {
            drag_active_state = active;
            if let Some(cb) = &on_window_drag_changed {
                cb(active);
            }
        }
    };

    let mut client: Option<PipeClient> = None;
    loop {
        if client.is_none() {
            if let Some(mut pipe) = PipeClient::connect(&pipe_wide) {
                let _ = pipe.drain_policies(&policy_store);
                set_conn(true);
                client = Some(pipe);
            } else {
                set_drag_active(false);
                set_conn(false);
                match rx.recv_timeout(Duration::from_millis(250)) {
                    Ok(WorkerCmd::Shutdown) | Err(mpsc::RecvTimeoutError::Disconnected) => break,
                    Ok(_) | Err(mpsc::RecvTimeoutError::Timeout) => continue,
                }
            }
        }

        if let Some(pipe) = &mut client
            && !pipe.drain_policies(&policy_store)
        {
            client = None;
            set_drag_active(false);
            set_conn(false);
            continue;
        }

        let live_drag = query_active_dragged_window();
        if let Some(pipe) = &mut client {
            if let Some(prev_hwnd) = pipe.active_drag_hwnd
                && !pipe.active_drag_synthetic
            {
                let edge = policy_store.lock().unwrap_or_else(|e| e.into_inner()).shared_edge;
                match live_drag {
                    Some((cur_hwnd, cur_rect)) if cur_hwnd == prev_hwnd => {
                        if pipe.last_sent_rect != Some(cur_rect) {
                            let (cx, cy) = query_cursor_pos();
                            let ev = DwcBridgeEvent {
                                kind: DwcEventKind::DragMove,
                                hwnd: cur_hwnd,
                                edge,
                                window_rect: cur_rect,
                                cursor_x: cx,
                                cursor_y: cy,
                                velocity_x: 0.0,
                                velocity_y: 0.0,
                                dnd_push: true,
                                dragging: true,
                            };
                            if pipe.write_event(&ev) {
                                pipe.last_sent_rect = Some(cur_rect);
                            } else {
                                client = None;
                                set_drag_active(false);
                                set_conn(false);
                                continue;
                            }
                        }
                    }
                    _ => {
                        pipe.active_drag_hwnd = None;
                        pipe.last_sent_rect = None;
                        let ev = DwcBridgeEvent {
                            kind: DwcEventKind::DragEnd,
                            hwnd: prev_hwnd,
                            edge,
                            window_rect: WindowRect::default(),
                            cursor_x: 0,
                            cursor_y: 0,
                            velocity_x: 0.0,
                            velocity_y: 0.0,
                            dnd_push: false,
                            dragging: false,
                        };
                        if !pipe.write_event(&ev) {
                            client = None;
                            set_drag_active(false);
                            set_conn(false);
                            continue;
                        }
                    }
                }
            }
            set_drag_active(live_drag.is_some() || pipe.active_drag_synthetic);
        }

        match rx.recv_timeout(Duration::from_millis(25)) {
            Ok(WorkerCmd::Shutdown) | Err(mpsc::RecvTimeoutError::Disconnected) => break,
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Ok(WorkerCmd::Probe { cursor_x, cursor_y }) => {
                if let Some(pipe) = &mut client
                    && let Some((hwnd, rect)) = query_active_dragged_window()
                {
                    let edge = policy_store.lock().unwrap_or_else(|e| e.into_inner()).shared_edge;
                    let kind = if pipe.active_drag_hwnd == Some(hwnd) {
                        DwcEventKind::DragMove
                    } else {
                        pipe.active_drag_hwnd = Some(hwnd);
                        DwcEventKind::DragBezelCross
                    };
                    pipe.active_drag_synthetic = false;
                    pipe.last_sent_rect = Some(rect);
                    set_drag_active(true);
                    let ev = DwcBridgeEvent {
                        kind,
                        hwnd,
                        edge,
                        window_rect: rect,
                        cursor_x,
                        cursor_y,
                        velocity_x: 0.0,
                        velocity_y: 0.0,
                        dnd_push: true,
                        dragging: true,
                    };
                    if !pipe.write_event(&ev) {
                        client = None;
                        set_drag_active(false);
                        set_conn(false);
                    }
                }
            }
            Ok(WorkerCmd::WindowDrag {
                hwnd,
                rect,
                cursor_x,
                cursor_y,
                dnd_push,
            }) => {
                if let Some(pipe) = &mut client {
                    let edge = policy_store.lock().unwrap_or_else(|e| e.into_inner()).shared_edge;
                    pipe.active_drag_hwnd = Some(hwnd);
                    pipe.active_drag_synthetic = true;
                    pipe.last_sent_rect = Some(rect);
                    set_drag_active(true);
                    let ev = DwcBridgeEvent {
                        kind: DwcEventKind::DragBezelCross,
                        hwnd,
                        edge,
                        window_rect: rect,
                        cursor_x,
                        cursor_y,
                        velocity_x: 0.0,
                        velocity_y: 0.0,
                        dnd_push,
                        dragging: true,
                    };
                    if !pipe.write_event(&ev) {
                        client = None;
                        set_drag_active(false);
                        set_conn(false);
                    }
                }
            }
            Ok(WorkerCmd::Unprobe) => {
                let current_drag = query_active_dragged_window();
                if let Some(pipe) = &mut client
                    && (pipe.active_drag_synthetic || current_drag.is_none())
                    && let Some(hwnd) = pipe.active_drag_hwnd.take()
                {
                    pipe.active_drag_synthetic = false;
                    pipe.last_sent_rect = None;
                    let edge = policy_store.lock().unwrap_or_else(|e| e.into_inner()).shared_edge;
                    let ev = DwcBridgeEvent {
                        kind: DwcEventKind::DragEnd,
                        hwnd,
                        edge,
                        window_rect: WindowRect::default(),
                        cursor_x: 0,
                        cursor_y: 0,
                        velocity_x: 0.0,
                        velocity_y: 0.0,
                        dnd_push: false,
                        dragging: false,
                    };
                    set_drag_active(current_drag.is_some());
                    if !pipe.write_event(&ev) {
                        client = None;
                        set_drag_active(false);
                        set_conn(false);
                    }
                }
            }
        }
    }

    set_drag_active(false);
    set_conn(false);
}

#[cfg(not(windows))]
fn worker_loop(
    _pipe_name: String,
    rx: mpsc::Receiver<WorkerCmd>,
    connected: Arc<AtomicBool>,
    _policy_store: Arc<Mutex<DwcPolicy>>,
    _on_connected_changed: Option<Arc<dyn Fn(bool) + Send + Sync>>,
    _on_window_drag_changed: Option<Arc<dyn Fn(bool) + Send + Sync>>,
) {
    connected.store(false, Ordering::Release);
    while let Ok(cmd) = rx.recv() {
        if matches!(cmd, WorkerCmd::Shutdown) {
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_wire_parity_with_dwc_cpp_server() {
        let ev = DwcBridgeEvent {
            kind: DwcEventKind::DragBezelCross,
            hwnd: 0x1234ABCD,
            edge: BezelEdge::Right,
            window_rect: WindowRect {
                left: 2160,
                top: 100,
                right: 3160,
                bottom: 800,
            },
            cursor_x: 2562,
            cursor_y: 420,
            velocity_x: 4.25,
            velocity_y: -0.5,
            dnd_push: true,
            dragging: true,
        };
        let json = ev.to_json_line();
        assert!(json.contains("\"type\":\"drag_bezel_cross\""));
        assert!(json.contains("\"hwnd\":\"0x1234abcd\""));
        assert!(json.contains("\"edge\":\"right\""));
        assert!(json.contains("\"left\":2160"));
        assert!(json.contains("\"dnd_push\":true"));

        let pol = DwcPolicy::parse_json_line(
            r#"{"type":"bezel_policy","shared_edge":"left","dnd_push_px":32,"suppress_aero_snap":true,"option_b_span":false}"#,
        )
        .expect("policy json must parse");
        assert_eq!(pol.shared_edge, BezelEdge::Left);
        assert_eq!(pol.dnd_push_px, 32);
        assert!(pol.suppress_aero_snap);
        assert!(!pol.option_b_span);
    }

    #[cfg(windows)]
    #[test]
    fn live_windows_named_pipe_roundtrip_receives_policy_and_sends_drag_events() {
        use std::ptr;
        use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
        use windows_sys::Win32::Storage::FileSystem::{FlushFileBuffers, PIPE_ACCESS_DUPLEX, ReadFile, WriteFile};
        use windows_sys::Win32::System::Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, PIPE_READMODE_BYTE, PIPE_TYPE_BYTE, PIPE_WAIT,
        };

        let pipe_name = r"\\.\pipe\dwc-oink-bridge-rust-test";
        let pipe_wide = encode_wide_nul(pipe_name);

        // SAFETY: Valid UTF-16 pipe name with standard duplex byte-stream server parameters.
        let srv = unsafe {
            CreateNamedPipeW(
                pipe_wide.as_ptr(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                1,
                8192,
                8192,
                0,
                ptr::null(),
            )
        };
        assert_ne!(srv, INVALID_HANDLE_VALUE);

        let srv_addr = srv as usize;
        let server_thread = thread::spawn(move || -> Vec<String> {
            let h = srv_addr as windows_sys::Win32::Foundation::HANDLE;
            // SAFETY: `h` is the server pipe handle exclusively moved into this test thread.
            let _ = unsafe { ConnectNamedPipe(h, ptr::null_mut()) };

            let policy_line =
                b"{\"type\":\"bezel_policy\",\"shared_edge\":\"right\",\"dnd_push_px\":30,\"suppress_aero_snap\":true,\"option_b_span\":true}\n";
            let mut written: u32 = 0;
            // SAFETY: `h` is connected and `policy_line` outlives `WriteFile`.
            let _ = unsafe { WriteFile(h, policy_line.as_ptr(), policy_line.len() as u32, &mut written, ptr::null_mut()) };
            // SAFETY: Flushing the server pipe handle before reading client replies.
            let _ = unsafe { FlushFileBuffers(h) };

            let mut collected = String::new();
            let mut buf = [0_u8; 2048];
            while collected.matches('\n').count() < 2 {
                let mut nread: u32 = 0;
                // SAFETY: `h` is valid and `buf` is uniquely borrowed.
                let ok = unsafe { ReadFile(h, buf.as_mut_ptr(), buf.len() as u32, &mut nread, ptr::null_mut()) };
                if ok == 0 || nread == 0 {
                    break;
                }
                collected.push_str(&String::from_utf8_lossy(&buf[..nread as usize]));
            }
            // SAFETY: Disconnect and close server pipe handle at test completion.
            unsafe {
                DisconnectNamedPipe(h);
                CloseHandle(h);
            }
            collected.lines().map(ToOwned::to_owned).collect()
        });

        let notified = Arc::new(AtomicBool::new(false));
        let notified_cb = Arc::clone(&notified);
        let handle = DwcBridgeHandle::start(
            pipe_name,
            Some(Arc::new(move |is_up| {
                if is_up {
                    notified_cb.store(true, Ordering::Release);
                }
            })),
        );

        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        while (!handle.is_connected() || handle.policy().dnd_push_px != 30) && std::time::Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        assert!(handle.is_connected());
        assert!(notified.load(Ordering::Acquire));
        assert_eq!(handle.policy().dnd_push_px, 30);

        handle.notify_window_drag(
            0xBEEF,
            WindowRect {
                left: 2000,
                top: 100,
                right: 3000,
                bottom: 800,
            },
            2560,
            400,
            true,
        );
        handle.notify_unprobe();

        let lines = server_thread.join().expect("server thread joined cleanly");
        drop(handle);

        assert_eq!(lines.len(), 2);
        assert!(lines[0].contains("\"type\":\"drag_bezel_cross\""));
        assert!(lines[0].contains("\"hwnd\":\"0xbeef\""));
        assert!(lines[1].contains("\"type\":\"drag_end\""));
    }

    #[cfg(windows)]
    #[test]
    fn window_drag_callback_stays_false_when_idle_and_toggles_only_on_drag() {
        use std::ptr;
        use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
        use windows_sys::Win32::Storage::FileSystem::{PIPE_ACCESS_DUPLEX, ReadFile};
        use windows_sys::Win32::System::Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, PIPE_READMODE_BYTE, PIPE_TYPE_BYTE, PIPE_WAIT,
        };

        let pipe_name = r"\\.\pipe\dwc-oink-bridge-drag-cb-test";
        let pipe_wide = encode_wide_nul(pipe_name);

        // SAFETY: Valid UTF-16 pipe name with standard duplex byte-stream server parameters.
        let srv = unsafe {
            CreateNamedPipeW(
                pipe_wide.as_ptr(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                1,
                8192,
                8192,
                0,
                ptr::null(),
            )
        };
        assert_ne!(srv, INVALID_HANDLE_VALUE);

        let srv_addr = srv as usize;
        let server_thread = thread::spawn(move || {
            let h = srv_addr as windows_sys::Win32::Foundation::HANDLE;
            // SAFETY: `h` is exclusively moved into this server thread.
            let _ = unsafe { ConnectNamedPipe(h, ptr::null_mut()) };
            let mut collected = String::new();
            let mut buf = [0_u8; 2048];
            while collected.matches('\n').count() < 2 {
                let mut nread: u32 = 0;
                // SAFETY: `h` is connected and `buf` is uniquely borrowed.
                let ok = unsafe { ReadFile(h, buf.as_mut_ptr(), buf.len() as u32, &mut nread, ptr::null_mut()) };
                if ok == 0 || nread == 0 {
                    break;
                }
                collected.push_str(&String::from_utf8_lossy(&buf[..nread as usize]));
            }
            // SAFETY: Disconnect and close server pipe handle at test completion.
            unsafe {
                DisconnectNamedPipe(h);
                CloseHandle(h);
            }
        });

        let drag_transitions: Arc<Mutex<Vec<bool>>> = Arc::new(Mutex::new(Vec::new()));
        let drag_cb = Arc::clone(&drag_transitions);
        let handle = DwcBridgeHandle::start_with_callbacks(
            pipe_name,
            None,
            Some(Arc::new(move |dragging| {
                drag_cb.lock().unwrap().push(dragging);
            })),
        );

        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        while !handle.is_connected() && std::time::Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        assert!(handle.is_connected());
        thread::sleep(Duration::from_millis(80));
        assert!(
            drag_transitions.lock().unwrap().is_empty(),
            "connecting pipe while idle must NOT activate window-drag bridge"
        );

        handle.notify_window_drag(
            0xCAFE,
            WindowRect {
                left: 100,
                top: 100,
                right: 900,
                bottom: 600,
            },
            2560,
            300,
            true,
        );

        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        while *drag_transitions.lock().unwrap() != [true] && std::time::Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(*drag_transitions.lock().unwrap(), [true]);

        handle.notify_unprobe();
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        while *drag_transitions.lock().unwrap() != [true, false] && std::time::Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(*drag_transitions.lock().unwrap(), [true, false]);

        let _ = server_thread.join();
        drop(handle);
    }
}
