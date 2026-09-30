# Sky Cream and Night Sky Theme Design

## Goal

Refresh the Android app's visual palette so it feels sunny, soft, modern, and
relaxed in light mode, while keeping a calm, low-glare night-sky variant in
dark mode.

## Scope

In scope:

- Replace the current iOS gray/blue Material 3 color tokens.
- Apply the new tokens to both light and dark themes.
- Preserve existing layout, interaction, permission, model, and navigation
  behavior.
- Keep error and disabled states distinguishable and readable.
- Verify the theme through existing Compose screens and build checks.

Out of scope:

- Rebuilding screen layouts or changing component hierarchy.
- Replacing the app icon or illustrations.
- Adding a user-facing theme picker.
- Changing typography, animation, or localization.

## Design language

The light palette is named **Sky Cream**. It uses an airy blue-white canvas,
clean sky-blue actions, soft blue message surfaces, and a restrained warm
apricot accent. It should feel bright without becoming saturated or childish.

The dark palette is named **Night Sky**. It uses a deep blue background instead
of pure black, slightly lighter blue surfaces, a soft light-blue primary, and a
warm cream secondary accent. It should remain comfortable for long sessions
and avoid the contrast shock of pure white text on pure black.

## Color tokens

### Light mode

| Token | Hex | Use |
|---|---|---|
| background | `#F7FBFF` | App canvas |
| surface | `#FFFFFF` | Cards, dialogs, assistant bubbles |
| surfaceVariant | `#E6F3FF` | Composer, model selector, secondary surfaces |
| primary | `#5A9BD6` | Main actions, selected states, icons |
| onPrimary | `#FFFFFF` | Text/icons on primary |
| secondary | `#F2B38A` | Warm accent, selected/attention highlights |
| onBackground/onSurface | `#243343` | Main text |
| onSurfaceVariant | `#748899` | Supporting text |
| outline | `#C8D9E8` | Borders and separators |
| error | `#D95C62` | Errors and destructive feedback |

### Dark mode

| Token | Hex | Use |
|---|---|---|
| background | `#101A28` | App canvas |
| surface | `#172538` | Cards, dialogs, assistant bubbles |
| surfaceVariant | `#223650` | Composer, model selector, secondary surfaces |
| primary | `#8CC4F4` | Main actions, selected states, icons |
| onPrimary | `#102033` | Text/icons on primary |
| secondary | `#F3CFAE` | Warm accent, selected/attention highlights |
| onBackground/onSurface | `#ECF5FC` | Main text |
| onSurfaceVariant | `#B4C7D9` | Supporting text |
| outline | `#3E5873` | Borders and separators |
| error | `#FF9A9F` | Errors and destructive feedback |

## Architecture

Keep the existing `AgentChatTheme` entry point and Material 3 color scheme.
Rename the palette constants in `Color.kt` to semantic Sky Cream/Night Sky
names, then make `Theme.kt` consume those constants. Existing screens already
read `MaterialTheme.colorScheme` for backgrounds, surfaces, buttons, message
bubbles, and controls, so the theme change remains centralized.

Avoid introducing hard-coded screen colors. If a component needs a distinction
not represented by Material tokens, use the closest semantic token with an
alpha adjustment rather than adding a one-off color.

## Accessibility and interaction states

- Main text remains dark blue-gray on the light canvas and mist-white on the
  dark canvas.
- Primary controls use white text in light mode and deep navy text in dark mode
  for legibility on blue surfaces.
- Error red is muted but remains visually separate from the blue palette.
- Disabled and supporting content use `onSurfaceVariant`, not reduced-opacity
  primary text.
- No interaction semantics or test tags change.

## Testing

- Add/adjust theme unit assertions for the key light and dark token values.
- Run the existing JVM unit tests.
- Compile Android and AndroidTest sources.
- Run Lint and assemble the Debug APK.
- Use the existing Compose UI coverage to ensure major surfaces still render;
  emulator-only failures caused by the known Android 17/Espresso
  `InputManager.getInstance` incompatibility remain environment limitations.

