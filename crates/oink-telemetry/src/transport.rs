//! Offline stub transport: all external telemetry and crash uploads are disabled.
use crate::{
    store::{Delivery, Submission},
    *,
};

#[derive(Clone)]
pub struct Endpoint {
    pub region: Region,
}
impl std::fmt::Debug for Endpoint {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Endpoint")
            .field("region", &self.region)
            .finish_non_exhaustive()
    }
}
impl Endpoint {
    pub fn parse(_dsn: &str, _region: Region, _policy: u16) -> Result<Self> {
        Err(Error::Invalid)
    }
    /// Build configuration only. Permanently disabled in this local-only build.
    pub fn compiled() -> Option<Self> {
        None
    }
}
#[derive(Debug)]
pub struct Https {
    endpoint: Endpoint,
}
impl Https {
    pub fn new(endpoint: Endpoint) -> Result<Self> {
        Ok(Self { endpoint })
    }
    pub async fn send(&self, _submission: &Submission) -> Delivery {
        let _ = &self.endpoint;
        Delivery::Drop
    }
}

#[cfg(test)]
fn limits(header: Option<&str>, retry: Option<&str>, status: u16, now: u64) -> Option<Delivery> {
    let mut errors_until = 0;
    let mut transactions_until = 0;
    if let Some(header) = header.filter(|h| h.len() <= 16384) {
        for entry in header.split(',').take(64) {
            let mut fields = entry.trim().split(':');
            let Some(seconds) = fields
                .next()
                .and_then(|v| v.parse::<f64>().ok())
                .filter(|v| v.is_finite() && *v >= 0.0)
            else {
                continue;
            };
            let until = now.saturating_add(seconds.ceil().min(u32::MAX as f64) as u64);
            let Some(categories) = fields.next() else { continue };
            if categories.is_empty() {
                errors_until = errors_until.max(until);
                transactions_until = transactions_until.max(until);
            }
            for category in categories.split(';') {
                match category {
                    "error" => errors_until = errors_until.max(until),
                    "transaction" | "span" => transactions_until = transactions_until.max(until),
                    _ => {}
                }
            }
        }
    } else if status == 429 {
        let delay = retry.and_then(|r| r.parse::<u64>().ok()).unwrap_or(60);
        errors_until = now.saturating_add(delay);
        transactions_until = errors_until;
    }
    if errors_until > 0 || transactions_until > 0 {
        Some(Delivery::Limited {
            errors_until,
            transactions_until,
        })
    } else if status == 429 {
        Some(Delivery::Drop)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn endpoint_is_disabled_in_local_only_build() {
        let key = "a".repeat(32);
        assert!(Endpoint::compiled().is_none());
        assert!(Endpoint::parse(&format!("https://{key}@o1.ingest.de.sentry.io/2"), Region::Eu, 3).is_err());
    }
    #[test]
    fn category_limits_do_not_relabel_or_backlog() {
        assert_eq!(
            limits(
                Some("60:error:organization,120:transaction:organization"),
                None,
                200,
                100
            ),
            Some(Delivery::Limited {
                errors_until: 160,
                transactions_until: 220
            })
        );
        assert_eq!(
            limits(None, Some("90"), 429, 100),
            Some(Delivery::Limited {
                errors_until: 190,
                transactions_until: 190
            })
        );
        assert_eq!(limits(Some("60:unknown:organization"), None, 200, 100), None);
    }
}

#[cfg(test)]
mod consent_network_tests {
    use super::*;
    #[tokio::test]
    async fn default_off_and_revoked_queues_make_no_connection() {
        let endpoint = Endpoint { region: Region::Eu };
        let https = Https::new(endpoint).unwrap();
        let dir = tempfile::tempdir().unwrap();
        let mut store = store::Store::open(dir.path(), ReportConsent::default(), now()).unwrap();
        let crash = Report::SafeCrash(SafeCrash::empty(
            BuildContext {
                app_version: AppVersion {
                    major: 1,
                    minor: 0,
                    patch: 0,
                    build: 1,
                },
                os_version_class: OsVersionClass::Unknown,
            },
            Component::Agent,
            CrashKind::RustPanic,
            ExceptionClass::Panic,
        ));
        assert_eq!(store.observe(0, crash.clone(), now(), now()), Err(Error::Disabled));
        if let Ok(Some(submission)) = store.prepare(0, now()) {
            assert_eq!(https.send(&submission).await, Delivery::Drop);
        }
        store
            .set_consent(ReportConsent {
                choice: Choice::Granted,
                policy_version: 3,
                generation: 1,
            })
            .unwrap();
        store.observe(1, crash, now(), now()).unwrap();
        store
            .set_consent(ReportConsent {
                choice: Choice::Denied,
                policy_version: 3,
                generation: 2,
            })
            .unwrap();
        if let Ok(Some(submission)) = store.prepare(1, now()) {
            assert_eq!(https.send(&submission).await, Delivery::Drop);
        }
        assert!(!store.has_work());
    }
}
