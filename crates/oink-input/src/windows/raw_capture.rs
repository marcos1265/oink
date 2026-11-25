//! Raw Input is suppressed by a swallowed LL mouse event. In a remote game,
//! allow physical MOVE while clipped under Oink's existing cursor-cover window.
//! A separate same-executable process restores the previous clip on parent death.
use std::io::{Read, Write};
use std::os::windows::process::CommandExt;
use std::process::{Child, ChildStdin, Command, ExitCode, Stdio};
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};
use windows::Win32::Foundation::{HWND, LPARAM, POINT, RECT, WPARAM};
use windows::Win32::System::Threading::CREATE_NO_WINDOW;
use windows::Win32::UI::WindowsAndMessaging::{GetClipCursor, PostMessageW};

use super::dpi::ThreadDpiGuard;
const ARG: &str = "--oink-raw-clip-guard";
const RETRY_BACKOFF: Duration = Duration::from_millis(500);

fn current() -> Option<RECT> {
    let mut rect = RECT::default();
    // SAFETY: valid output rectangle, thread uses physical coordinates.
    unsafe { GetClipCursor(&mut rect) }.ok()?;
    Some(rect)
}
fn restore(previous: RECT, owned: RECT) {
    super::cursor_clip::restore(previous, owned);
}

/// Call before initializing the GUI, logger, input backend or CLI parser.
/// EOF on the private stdin pipe is the parent's lifetime signal. A normal
/// shutdown writes D (disarm) before closing, so a delayed old helper cannot
/// restore over a newly created lease at the same park point.
pub fn dispatch_guard() -> Option<ExitCode> {
    let args: Vec<_> = std::env::args().collect();
    if args.get(1).map(String::as_str) != Some(ARG) {
        return None;
    }
    let _dpi = ThreadDpiGuard::per_monitor_v2();
    let numbers: Option<Vec<i32>> = args.iter().skip(2).map(|s| s.parse().ok()).collect();
    let Some(v) = numbers.filter(|v| v.len() == 8) else {
        return Some(ExitCode::FAILURE);
    };
    let previous = RECT {
        left: v[0],
        top: v[1],
        right: v[2],
        bottom: v[3],
    };
    let owned = RECT {
        left: v[4],
        top: v[5],
        right: v[6],
        bottom: v[7],
    };
    if std::io::stdout()
        .write_all(b"R")
        .and_then(|_| std::io::stdout().flush())
        .is_err()
    {
        return Some(ExitCode::FAILURE);
    }
    let mut command = [0];
    // The worker may be cancelled before the hook actually acquires the clip.
    // Only A arms restoration; EOF before A has no side effects.
    if std::io::stdin().read_exact(&mut command).is_ok()
        && command[0] == b'A'
        && (std::io::stdin().read_exact(&mut command).is_err() || command[0] != b'D')
    {
        restore(previous, owned);
    }
    Some(ExitCode::SUCCESS)
}

#[derive(Debug)]
struct Lease {
    armed: bool,
    previous: RECT,
    owned: RECT,
    child: Child,
    pipe: Option<ChildStdin>,
}
impl Lease {
    fn spawn(previous: RECT, owned: RECT) -> std::io::Result<Self> {
        let coords = [
            previous.left,
            previous.top,
            previous.right,
            previous.bottom,
            owned.left,
            owned.top,
            owned.right,
            owned.bottom,
        ];
        let mut child = Command::new(std::env::current_exe()?)
            .arg(ARG)
            .args(coords.map(|v| v.to_string()))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .creation_flags(CREATE_NO_WINDOW.0)
            .spawn()?;
        let pipe = child.stdin.take();
        let mut lease = Self {
            armed: false,
            previous,
            owned,
            child,
            pipe,
        };
        // Runs on a worker, never on the hook thread. Only our early entry point
        // writes this readiness byte; no clip is acquired until it is observed.
        let mut stdout = lease.child.stdout.take().unwrap();
        let (ready_tx, ready_rx) = mpsc::channel();
        std::thread::spawn(move || {
            let mut ready = [0];
            let result = stdout.read_exact(&mut ready).map(|_| ready);
            let _ = ready_tx.send(result);
        });
        if !matches!(ready_rx.recv_timeout(Duration::from_secs(2)), Ok(Ok([b'R']))) {
            let _ = lease.child.kill();
            let _ = lease.child.wait();
            return Err(std::io::Error::other("cursor guard handshake failed"));
        }
        Ok(lease)
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        if self.armed {
            restore(self.previous, self.owned);
        }
        if let Some(mut pipe) = self.pipe.take() {
            let _ = pipe.write_all(b"D");
        }
        // Reap without blocking the hook thread.
        let _ = self.child.try_wait();
    }
}

#[derive(Default)]
pub(super) struct RawCapture {
    pending: Option<Receiver<std::io::Result<Lease>>>,
    lease: Option<Lease>,
    retry_after: Option<Instant>,
}
impl RawCapture {
    /// Woken via WM_APP_RAW_CAPTURE_READY and refreshed every 200 ms while active;
    /// false is also used before restoring local position.
    pub fn update(&mut self, enabled: bool, park: POINT, wake_hwnd: isize) {
        if !enabled {
            if self.lease.is_some() {
                tracing::info!(active = false, "remote game raw capture released");
            }
            self.pending = None;
            self.lease = None;
            self.retry_after = None;
            return;
        }
        let expected_owned = RECT {
            left: park.x,
            top: park.y,
            right: park.x.saturating_add(1),
            bottom: park.y.saturating_add(1),
        };
        if let Some(lease) = &mut self.lease {
            if lease.owned == expected_owned
                && matches!(lease.child.try_wait(), Ok(None))
                && current() == Some(lease.owned)
            {
                return;
            }
            // Guard was lost or another app changed confinement: stop allowing
            // MOVE until a fresh guard/clip is ready. Never let the cursor escape.
            tracing::warn!(active = false, "remote game raw capture lost guard or cursor clip");
            super::trace::action(crate::native_lifecycle::Kind::ClipChanged, None, 0, None);
            self.lease = None;
        }
        if let Some(pending) = &self.pending {
            match pending.try_recv() {
                Ok(Ok(mut lease)) => {
                    self.pending = None;
                    if lease.owned == expected_owned
                        && current() == Some(lease.previous)
                        && lease.pipe.as_mut().is_some_and(|p| p.write_all(b"A").is_ok())
                    {
                        lease.armed = true;
                        // SAFETY: a one-pixel rectangle at the existing covered park point.
                        let result = super::cursor_clip::acquire(lease.owned, lease.previous);
                        super::trace::clip_action(lease.owned, 1, result.is_ok());
                        if result.is_ok() {
                            tracing::info!(active = true, "remote game raw capture ready");
                            self.lease = Some(lease);
                            self.retry_after = None;
                            return;
                        }
                    }
                    self.retry_after = Some(Instant::now() + RETRY_BACKOFF);
                    return;
                }
                Ok(Err(e)) => {
                    self.pending = None;
                    self.retry_after = Some(Instant::now() + RETRY_BACKOFF);
                    tracing::warn!(%e, "cannot start game cursor guard");
                    return;
                }
                Err(mpsc::TryRecvError::Empty) => return,
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.pending = None;
                    self.retry_after = Some(Instant::now() + RETRY_BACKOFF);
                    return;
                }
            }
        }
        if self.retry_after.is_some_and(|until| Instant::now() < until) {
            return;
        }
        let Some(previous) = current() else { return };
        let owned = expected_owned;
        let (tx, rx) = mpsc::channel();
        self.pending = Some(rx);
        if let Err(e) = std::thread::Builder::new()
            .name("oink-clip-guard".into())
            .spawn(move || {
                let result = Lease::spawn(previous, owned);
                let _ = tx.send(result);
                if wake_hwnd != 0 {
                    // SAFETY: best-effort notification to the hook thread's message window.
                    unsafe {
                        let _ = PostMessageW(
                            Some(HWND(wake_hwnd as *mut _)),
                            super::hook::WM_APP_RAW_CAPTURE_READY,
                            WPARAM(0),
                            LPARAM(0),
                        );
                    }
                }
            })
        {
            self.pending = None;
            self.retry_after = Some(Instant::now() + RETRY_BACKOFF);
            tracing::warn!(%e, "cannot start game cursor guard worker");
        }
    }
    pub fn allows_move(&self) -> bool {
        self.lease.as_ref().is_some_and(|l| current() == Some(l.owned))
    }
}

#[cfg(all(test, debug_assertions))]
mod tests {
    use super::*;

    #[test]
    fn disabling_raw_capture_clears_retry_backoff() {
        let mut capture = RawCapture {
            retry_after: Some(Instant::now() + RETRY_BACKOFF),
            ..Default::default()
        };
        capture.update(false, POINT { x: 100, y: 200 }, 0);
        assert!(capture.retry_after.is_none());
        assert!(capture.pending.is_none());
        assert!(capture.lease.is_none());
    }
}
