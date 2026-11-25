//! Local-only stub: remote sponsor fetching has been removed.
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct LocalizedText {
    default: String,
    #[serde(default)]
    locales: HashMap<String, String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(tag = "type", rename_all = "lowercase", deny_unknown_fields)]
pub(crate) enum Action {
    Link {
        url: String,
    },
    Qr {
        image: String,
        caption: Option<LocalizedText>,
    },
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct SponsorItem {
    id: String,
    name: LocalizedText,
    icon: String,
    action: Action,
}

pub(crate) struct SponsorList;

impl SponsorList {
    #[cfg(any(target_os = "macos", windows))]
    pub(crate) fn start() -> Self {
        Self
    }
}

#[cfg(any(target_os = "macos", windows))]
#[tauri::command]
pub(crate) async fn shell_sponsor_list(_state: tauri::State<'_, SponsorList>) -> Result<Option<Vec<SponsorItem>>, ()> {
    Ok(None)
}
