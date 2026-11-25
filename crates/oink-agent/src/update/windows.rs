use super::*;
pub(super) fn status() -> ApiResult<UpdateStatus> {
    Err(api_error(Error::NotConfigured))
}
pub(super) fn command(_node: &Node, _command: Command) -> ApiResult<UpdateStatus> {
    Err(api_error(Error::NotConfigured))
}
pub(super) fn attach(_node: &Node) {}
pub(super) fn user_quit() -> bool {
    true
}
pub(crate) fn startup_gate() -> Result<(), Error> {
    Ok(())
}
