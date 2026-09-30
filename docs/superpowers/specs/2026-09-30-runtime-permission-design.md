# Runtime Permission Flow Design

## Goal

Make device capabilities opt-in at the point of use. When a capability is
needed and its Android runtime permission is missing, the app opens the
corresponding system authorization dialog. After authorization, the original
user action continues automatically.

## Scope

In scope:

- Location permission for current-location weather/search requests.
- Microphone permission for voice input.
- Calendar read permission for calendar-aware answers.
- Calendar write permission for confirmed event creation.
- Notification permission when background completion notifications are first
  needed on Android 13 and newer.
- Recovery to the app settings page after a permission has been permanently
  denied.
- Refreshing permission and location-service state when the activity resumes.

Out of scope:

- Requesting all permissions at app startup.
- Runtime prompts for `INTERNET`, foreground-service, or other install-time
  permissions that Android does not expose as runtime dialogs.
- Reading or changing permissions without an explicit user action.

## Architecture

`MainActivity` remains the lifecycle owner of Android permission launchers.
Feature code reports a pending action and the activity owns the sequence:

```text
user action
    -> capability preflight
    -> system permission dialog (when missing)
    -> resume pending action after result
    -> settings fallback when permanently denied
```

The implementation uses one consistent helper/flow for checking missing
permissions, launching `RequestPermission` or `RequestMultiplePermissions`,
and opening `ACTION_APPLICATION_DETAILS_SETTINGS` when another request would
not show a useful system dialog. Pending actions are one-shot and are cleared
on cancellation, denial, or completion so an old request cannot be replayed.

## Trigger behavior

### Location

Before sending a query that needs the device's current location (for example,
current-location weather without an explicit city), check coarse/fine
location. If missing, request both permissions and resume the send after a
successful result. If permission is granted but the system location service is
off, open the system location settings page. City-specific weather does not
require device location.

### Microphone

When voice capture starts, request `RECORD_AUDIO` if it is missing. A granted
result starts the existing voice controller. A denial returns to text input;
permanent denial offers the app settings page.

### Calendar

Calendar questions request `READ_CALENDAR` before the message is sent and then
resume the message. Event creation requests `WRITE_CALENDAR` (and read access
when required by the existing flow) only after the user confirms the draft.
Successful authorization resumes the read or insert operation; denial keeps
the confirmation card/action available and shows a concise explanation.

### Notifications

Do not request notification permission on first launch. Request it when the
app first enters a background-capable agent turn that needs a completion
notification. If denied, the agent turn continues and only the notification
delivery is unavailable; the UI offers settings recovery where appropriate.

## UX and failure handling

- No permission dialog is shown for unrelated actions.
- Permission result callbacks refresh the visible capability status.
- A permanently denied permission shows a clear message and a `去设置`
  action; it does not repeatedly launch a no-op request.
- A missing system location switch opens location settings rather than app
  details.
- Permission/API-key errors do not expose secrets or raw private content.

## Testing

JVM tests cover pure permission requirement classification and pending-action
state transitions. Android UI tests cover:

- missing permission launches the relevant request path;
- successful results resume voice, location send, calendar read, and calendar
  write actions;
- denial does not run the protected action;
- settings fallback is exposed for permanent denial;
- startup does not request all runtime permissions;
- resume refreshes permission and location-service state.

