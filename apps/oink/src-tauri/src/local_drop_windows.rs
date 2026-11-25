//! Package drop updater disabled in local-only build.
pub fn attach(_window: &tauri::WebviewWindow) -> tauri::Result<()> {
    Ok(())
}
